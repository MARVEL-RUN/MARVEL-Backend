package kr.co.teambrain.marvelrun.admin.payment.command.batch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.payment.command.AdminRefundPrepared;
import kr.co.teambrain.marvelrun.admin.payment.command.AdminRefundTime;
import kr.co.teambrain.marvelrun.admin.payment.command.batch.AdminRefundBatchModels.*;

/** 배치 저장만 담당한다. 금융 행과 함께 잠그거나 외부 PG를 호출하지 않는다. */
@Repository
@RequiredArgsConstructor
public class AdminRefundBatchStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AdminRefundTime time;

    /** 요청 동일성 확인은 명단 재확장 전에 수행한다. */
    @Transactional(readOnly = true)
    public String existing(String eventId, String requestId, String fingerprint, String adminId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id,fingerprint,admin_id from admin_refund_batch where event_id=? and request_id=?", eventId, requestId);
        if (rows.isEmpty()) { return null; }
        Map<String, Object> row = rows.getFirst();
        if (!fingerprint.equals(row.get("fingerprint")) || !adminId.equals(row.get("admin_id"))) {
            throw new CustomException(ErrorCode.REGISTRATION_MODIFICATION_PAYMENT_CONFLICT);
        }
        return (String) row.get("id");
    }

    /** 요청 UNIQUE 제약으로 중복 접수를 차단한다. 생성에 성공한 호출만 동기 실행한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String create(String eventId, String requestId, String adminId, String reason,
            Operation operation, String fingerprint, List<Target> targets) {
        if (targets.isEmpty() || targets.size() > AdminRefundBatchSelection.MAX_EXPANDED_TARGETS) {
            throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_ARGUMENT);
        }
        String batchId = UUID.randomUUID().toString();
        LocalDateTime now = time.now();
        jdbc.update("""
                insert into admin_refund_batch(id,event_id,request_id,admin_id,reason,operation,fingerprint,status,total,accepted_at,updated_at)
                values(?,?,?,?,?,?,?,'PENDING',?,?,?)
                """, batchId, eventId, requestId, adminId, reason, operation.name(), fingerprint, targets.size(), now, now);
        int index = 0;
        for (Target target : targets) {
            jdbc.update("""
                    insert into admin_refund_batch_item(batch_id,item_no,registration_id,organization_id,target_json,status,error_code,finished_at)
                    values(?,?,?,?,?,?,?,?)
                    """, batchId, index++, target.registrationId(), target.organizationId(), json(target),
                    target.errorCode() == null ? "PENDING" : "BLOCKED", target.errorCode(), target.errorCode() == null ? null : now);
        }
        finishBatchIfDone(batchId, now);
        return batchId;
    }

    /** 작업 하나의 소유권을 먼저 커밋한다. 이전 프로세스 RUNNING 작업은 자동 재전송하지 않는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Work claim(String batchId) {
        List<Map<String, Object>> batches = jdbc.queryForList("select * from admin_refund_batch where id=? and status='PENDING' for update", batchId);
        if (batches.isEmpty()) { return null; }
        Map<String, Object> batch = batches.getFirst();
        List<Map<String, Object>> items = jdbc.queryForList("select item_no,target_json from admin_refund_batch_item where batch_id=? and status='PENDING' order by item_no limit 1 for update", batchId);
        if (items.isEmpty()) { finishBatchIfDone(batchId, time.now()); return null; }
        Map<String, Object> item = items.getFirst();
        int number = ((Number) item.get("item_no")).intValue();
        LocalDateTime now = time.now();
        jdbc.update("update admin_refund_batch set status='RUNNING',updated_at=? where id=?", now, batchId);
        jdbc.update("update admin_refund_batch_item set status='RUNNING',started_at=? where batch_id=? and item_no=?", now, batchId, number);
        return new Work(batchId, number, (String) batch.get("event_id"), (String) batch.get("admin_id"),
                (String) batch.get("request_id"), (String) batch.get("reason"), Operation.valueOf((String) batch.get("operation")),
                read(jsonText(item.get("target_json")), Target.class));
    }

    /** 준비 커밋 결과를 PG 호출 전에 보존한다. 저장 실패 시 PG 호출을 시작하지 않는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void prepared(Work work, AdminRefundPrepared prepared) {
        requireOne(jdbc.update("update admin_refund_batch_item set preparation_json=? where batch_id=? and item_no=? and status='RUNNING'",
                json(prepared), work.batchId(), work.itemNo()));
    }

    /** 종료 결과와 작업 소유권 반환을 같은 배치 전용 트랜잭션에 저장한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(Work work, String status, String errorCode, Object result) {
        if (!Set.of("SUCCEEDED", "BLOCKED", "FAILED", "NEEDS_REVIEW").contains(status)) { throw new IllegalArgumentException("배치 종료 상태 오류"); }
        jdbc.queryForObject("select id from admin_refund_batch where id=? for update", String.class, work.batchId());
        LocalDateTime now = time.now();
        requireOne(jdbc.update("update admin_refund_batch_item set status=?,error_code=?,result_json=?,finished_at=? where batch_id=? and item_no=? and status='RUNNING'",
                status, errorCode, result == null ? null : json(result), now, work.batchId(), work.itemNo()));
        jdbc.update("update admin_refund_batch set status='PENDING',updated_at=? where id=?", now, work.batchId());
        finishBatchIfDone(work.batchId(), now);
    }

    /** 대상별 집계이며 배치 완료와 환불 성공을 분리한다. */
    @Transactional(readOnly = true)
    public Summary summary(String eventId, String batchId) {
        List<Summary> rows = jdbc.query("select * from admin_refund_batch where event_id=? and id=?",
                (rs, index) -> new Summary(rs.getString("id"), rs.getString("request_id"), rs.getString("operation"),
                        rs.getString("status"), timestamp(rs,"accepted_at"), timestamp(rs,"updated_at"), timestamp(rs,"completed_at"),
                        rs.getInt("total"), new LinkedHashMap<>()), eventId, batchId);
        if (rows.isEmpty()) { throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_ARGUMENT); }
        Summary summary = rows.getFirst();
        jdbc.query("select status,count(*) as n from admin_refund_batch_item where batch_id=? group by status",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> summary.counts().put(rs.getString("status"),rs.getLong("n")), batchId);
        return summary;
    }
    /** 민감한 원 PG 응답 대신 실행기의 제한된 증거만 반환한다. */
    @Transactional(readOnly = true)
    public Items items(String eventId, String batchId, int page, int size, boolean exceptionsOnly) {
        summary(eventId, batchId);
        if (page < 0 || page > 100000 || size < 1 || size > 100) { throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_ARGUMENT); }
        String condition = exceptionsOnly ? " and status in ('BLOCKED','FAILED','NEEDS_REVIEW')" : "";
        Long count = jdbc.queryForObject("select count(*) from admin_refund_batch_item where batch_id=?" + condition, Long.class, batchId);
        List<Item> rows = jdbc.query("select * from admin_refund_batch_item where batch_id=?" + condition + " order by item_no limit ? offset ?",
                (rs, index) -> new Item(rs.getInt("item_no"), rs.getString("registration_id"), rs.getString("organization_id"), rs.getString("status"),
                        rs.getString("error_code"), timestamp(rs,"started_at"), timestamp(rs,"finished_at"),
                        tree(rs.getString("preparation_json")), tree(rs.getString("result_json"))), batchId, size, (long) page*size);
        return new Items(page,size,count == null ? 0 : count,rows);
    }
    /** 응답 유실 시 최초 요청 ID로 조회한다. 조회는 어떤 환불도 실행하지 않는다. */
    @Transactional(readOnly = true)
    public Response byRequest(String eventId, String requestId, String adminId) {
        List<String> ids = jdbc.queryForList(
                "select id from admin_refund_batch where event_id=? and request_id=? and admin_id=?",
                String.class, eventId, requestId, adminId);
        if (ids.isEmpty()) { throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_ARGUMENT); }
        return response(eventId, ids.getFirst());
    }

    /** 전체 결과도 접수 상한까지만 반환한다. 과거 대량 배치는 기존 페이지 조회로 확인한다. */
    @Transactional(readOnly = true)
    public Response response(String eventId, String batchId) {
        Summary summary = summary(eventId, batchId);
        Items page = items(eventId, batchId, 0, AdminRefundBatchSelection.MAX_EXPANDED_TARGETS, false);
        return new Response(summary, page.items(), page.total() > page.items().size());
    }

    /** 모든 대상이 종료되면 배치도 종료하되 예외 수는 별도 집계한다. */
    private void finishBatchIfDone(String id, LocalDateTime now) {
        jdbc.update("""
                update admin_refund_batch b set b.status='COMPLETED',b.updated_at=?,b.completed_at=? where b.id=?
                and not exists(select 1 from admin_refund_batch_item i where i.batch_id=b.id and i.status in ('PENDING','RUNNING'))
                """, now, now, id);
    }
    /** DB 기록 실패를 성공으로 숨기지 않는다. */
    private static void requireOne(int count) { if (count != 1) { throw new IllegalStateException("배치 기록 변경 건수 불일치"); } }
    /** 저장 JSON은 Spring의 JavaTime 설정을 그대로 사용한다. */
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (JsonProcessingException error) { throw new IllegalStateException("배치 직렬화 실패", error); } }
    /** MySQL 드라이버의 JSON 반환 형식 차이를 처리한다. */
    private static String jsonText(Object raw) {
        return raw instanceof byte[] bytes ? new String(bytes,java.nio.charset.StandardCharsets.UTF_8) : raw.toString();
    }
    private <T> T read(String text, Class<T> type) { try { return mapper.readValue(text,type); } catch (JsonProcessingException error) { throw new IllegalStateException("배치 읽기 실패", error); } }
    /** 내부 거래 키는 저장 증거에만 보존하고 프론트 응답에서 제외한다. */
    private JsonNode tree(String text) {
        if (text == null) { return null; }
        JsonNode node=read(text,JsonNode.class);
        removeKeys(node);
        return node;
    }
    /** 목록과 중첩 객체에도 같은 키 제외 규칙을 적용한다. */
    private void removeKeys(JsonNode node) {
        if (node.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode object=(com.fasterxml.jackson.databind.node.ObjectNode) node;
            object.remove(List.of("transactionKey","paymentKey","idempotencyKey","secretKey"));
        }
        node.forEach(this::removeKeys);
    }
    private static LocalDateTime timestamp(ResultSet rs,String column) throws SQLException {
        java.sql.Timestamp value=rs.getTimestamp(column); return value==null?null:value.toLocalDateTime();
    }
}
