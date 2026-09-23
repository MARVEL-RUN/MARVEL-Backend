package kr.co.teambrain.marvelrun.admin.payment.command.evidence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Isolation;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.payment.query.AdminPaymentQueryRepository;
import kr.co.teambrain.marvelrun.admin.payment.command.evidence.AdminRefundEvidenceModels.*;

/** 금융 테이블은 읽기만 하고 증거 테이블에만 append한다. */
@Repository
@RequiredArgsConstructor
public class AdminRefundEvidenceStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AdminPaymentQueryRepository scope;

    /** 취소의 원결제와 대회 소속을 검증한 후 제한된 스냅샷을 읽는다. */
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ, propagation=Propagation.REQUIRES_NEW)
    public Snapshot snapshot(String eventId,String cancelId) {
        requireId(eventId); requireId(cancelId);
        List<Snapshot> rows=jdbc.query("""
                select c.id,c.payment_id,c.cancel_amount,c.status,p.payment_key,p.order_id,p.amount
                from payment_cancel c join payment p on p.id=c.payment_id where c.id=?
                """,(rs,i) -> new Snapshot(rs.getString("id"),rs.getString("payment_id"),rs.getString("payment_key"),
                        rs.getString("order_id"),rs.getBigDecimal("amount"),rs.getBigDecimal("cancel_amount"),
                        rs.getString("status"),List.of()),cancelId);
        if (rows.size()!=1 || !scope.paymentBelongsToEvent(eventId,rows.getFirst().paymentId())) { throw invalid(); }
        Snapshot row=rows.getFirst();
        // 동일 취소 시도에 기록된 키만 사용한다. 다른 취소의 최근 거래 키를 가져오지 않는다.
        List<String> keys=jdbc.queryForList("""
                select distinct transaction_key from payment_process_log
                where payment_cancel_id=? and payment_id=? and transaction_key is not null
                  and transaction_key<>'' order by transaction_key limit 2
                """,String.class,cancelId,row.paymentId());
        return new Snapshot(row.paymentCancelId(),row.paymentId(),row.paymentKey(),row.orderId(),row.totalAmount(),
                row.cancelAmount(),row.localStatus(),List.copyOf(keys));
    }
    /** 증거 저장 실패를 성공 응답으로 숨기지 않는다. 금융 원장에는 쓰지 않는다. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Evidence append(Evidence evidence) {
        int changed=jdbc.update("""
                insert into admin_refund_evidence(id,event_id,payment_cancel_id,payment_id,checked_by,checked_at,evidence_json)
                values(?,?,?,?,?,?,?)
                """,evidence.evidenceId(),evidence.eventId(),evidence.paymentCancelId(),evidence.paymentId(),
                evidence.checkedBy(),evidence.checkedAt(),json(evidence));
        if(changed!=1) { throw new IllegalStateException("환불 조회 증거 저장 실패"); }
        return evidence;
    }
    /** 조회 시에도 원결제 대회 소속을 검증하며 기록 1페이지에 최대 100건만 반환한다. */
    @Transactional(readOnly=true)
    public Page list(String eventId,String cancelId,int page,int size) {
        requireId(eventId); requireId(cancelId);
        if(page<0 || page>100000 || size<1 || size>100) { throw invalid(); }
        List<String> payments=jdbc.queryForList("select payment_id from payment_cancel where id=?",String.class,cancelId);
        if(payments.size()!=1 || !scope.paymentBelongsToEvent(eventId,payments.getFirst())) { throw invalid(); }
        List<Evidence> rows=jdbc.query("""
                select evidence_json from admin_refund_evidence where event_id=? and payment_cancel_id=?
                order by checked_at desc,id desc limit ? offset ?
                """,(rs,i) -> read(rs.getString("evidence_json")),eventId,cancelId,size,(long)page*size);
        return new Page(page,size,rows);
    }
    /** 식별자 상한으로 비정상 입력을 외부 요청 전에 거절한다. */
    private static void requireId(String id) { if(id==null || id.isBlank() || id.length()>40) { throw invalid(); } }
    private static CustomException invalid() { return new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_ARGUMENT); }
    /** 스프링의 시간 모듈 설정을 그대로 사용한다. */
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch(JsonProcessingException error) { throw new IllegalStateException("증거 직렬화 실패",error); }
    }
    /** 저장된 증거만 복원하며 토스 재조회는 하지 않는다. */
    private Evidence read(String json) {
        try { return mapper.readValue(json,Evidence.class); }
        catch(JsonProcessingException error) { throw new IllegalStateException("증거 역직렬화 실패",error); }
    }
}
