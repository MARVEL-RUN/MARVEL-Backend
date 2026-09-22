package kr.co.teambrain.marvelrun.admin.payment.command.batch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.payment.command.dto.*;
import kr.co.teambrain.marvelrun.admin.payment.command.batch.AdminRefundBatchModels.Target;

/** 단체 전체를 유한한 명단으로 확장하고 중복 선택은 한 번만 처리한다. */
@Component
@RequiredArgsConstructor
public class AdminRefundBatchSelection {
    public static final int MAX_EXPANDED_TARGETS = 100;
    private final NamedParameterJdbcTemplate jdbc;

    /** 이벤트 존재와 소속은 DB에서 확인하며 접수 시 버전을 고정한다. */
    @org.springframework.transaction.annotation.Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<Target> full(String eventId, AdminPaymentRefundRequest request) {
        requireEvent(eventId);
        checkSize(request.registrationIds().size() + request.organizationIds().size());
        Map<String, Target> selected = new TreeMap<>();
        for (String id : request.registrationIds().stream().distinct().sorted().toList()) {
            List<Target> rows = registrations(eventId, "id=:target", id, 1);
            selected.put("r:" + id, rows.isEmpty() ? new Target(id, null, null, null, "REGISTRATION_NOT_FOUND") : rows.getFirst());
        }
        for (String id : request.organizationIds().stream().distinct().sorted().toList()) {
            Integer exists = jdbc.queryForObject("select count(*) from organization where id=:target and event_id=:event",
                    Map.of("target", id, "event", eventId), Integer.class);
            if (exists == null || exists == 0) {
                selected.put("o:" + id, new Target(null, id, null, null, "ORGANIZATION_NOT_FOUND"));
                continue;
            }
            String after = null;
            boolean found = false;
            while (true) {
                Map<String,Object> args = new java.util.HashMap<>();
                args.put("event", eventId); args.put("target", id);
                String cursor = after == null ? "" : " and id > :after";
                if (after != null) { args.put("after", after); }
                List<Target> rows = jdbc.query("select id,organization_id,version from registration "
                        + "where event_id=:event and organization_id=:target and is_del=false" + cursor
                        + " order by id limit 20", args,
                        (rs,index) -> new Target(rs.getString("id"),rs.getString("organization_id"),
                                rs.getObject("version",Long.class),null,null));
                if (rows.isEmpty()) { break; }
                found = true;
                for (Target row : rows) { selected.put("r:" + row.registrationId(), row); }
                checkSize(selected.size());
                after = rows.getLast().registrationId();
            }
            if (!found) { selected.put("o:" + id, new Target(null, id, null, null, "EMPTY_ORGANIZATION")); }
            checkSize(selected.size());
        }
        checkSize(selected.size());
        return List.copyOf(selected.values());
    }

    /** 부분환불 후보는 요청 ID 순서와 무관하게 한 참가자당 한 번만 구성한다. */
    @org.springframework.transaction.annotation.Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<Target> partial(String eventId, AdminPaymentPartialRefundRequest request) {
        requireEvent(eventId);
        checkSize(request.targets().size());
        List<Target> result = new ArrayList<>();
        for (AdminPaymentPartialRefundTarget change : request.targets().stream()
                .sorted(java.util.Comparator.comparing(AdminPaymentPartialRefundTarget::registrationId)).toList()) {
            List<Target> rows = registrations(eventId, "id=:target", change.registrationId(), 1);
            Target original = rows.isEmpty() ? new Target(change.registrationId(), null, null, null, "REGISTRATION_NOT_FOUND") : rows.getFirst();
            result.add(new Target(original.registrationId(), original.organizationId(), original.version(), change, original.errorCode()));
        }
        return List.copyOf(result);
    }

    /** 금액·상태는 실행 직전에 기존 잠금 서비스가 다시 검증한다. */
    private List<Target> registrations(String eventId, String scope, String id, int limit) {
        return jdbc.query("select id,organization_id,version from registration where event_id=:event and " + scope
                + " order by id limit :limit", Map.of("event", eventId, "target", id, "limit", limit),
                (rs, index) -> new Target(rs.getString("id"), rs.getString("organization_id"),
                        rs.getObject("version", Long.class), null, null));
    }
    /** 다른 대회의 ID를 요청 경로에 조합할 수 없다. */
    private void requireEvent(String eventId) {
        if (eventId == null || eventId.isBlank() || eventId.length() > 40) { invalid(); }
        Integer count = jdbc.queryForObject("select count(*) from event where id=:id", Map.of("id", eventId), Integer.class);
        if (count == null || count == 0) { throw new CustomException(ErrorCode.EVENT_NOT_FOUND); }
    }
    /** 확장 초과는 일부만 접수하지 않고 전체 접수 전에 거절한다. */
    private static void checkSize(int size) { if (size < 1 || size > MAX_EXPANDED_TARGETS) { invalid(); } }
    private static void invalid() { throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_ARGUMENT); }
}
