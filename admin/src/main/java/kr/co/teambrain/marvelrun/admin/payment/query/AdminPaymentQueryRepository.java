package kr.co.teambrain.marvelrun.admin.payment.query;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 필요한 컬럼만 조회한다. 금융 이력은 부모 주문 페이지를 먼저 정하고 귀속·취소를 일괄 조회한다. */
@Repository
@RequiredArgsConstructor
public class AdminPaymentQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;

    private static final String PERSONAL_SCOPE = """
            p.organization_id is null and (
                p.registration_id = :targetId or exists (
                    select 1 from payment_allocation a
                    where a.payment_id = p.id and a.registration_id = :targetId
                )
            )
            """;
    private static final String GROUP_SCOPE = "p.organization_id = :targetId";
    private static final String REGISTRATION_SELECT = """
            select r.id, r.name, r.organization_id, r.contract_amount, r.status
            from registration r
            """;

    /** 신청의 대회·개인/단체 연결 및 현재 금액을 읽는다. 삭제된 신청도 상세 금융 대상이 될 수 있다. */
    public List<Map<String, Object>> registration(String eventId, String registrationId) {
        return rows(REGISTRATION_SELECT + " where r.event_id=:eventId and r.id=:id",
                Map.of("eventId", eventId, "id", registrationId));
    }

    /** 단체장과 현재 활성 구성원의 합계를 읽는다. 과거 납부액을 현재 합계에 혼합하지 않는다. */
    public List<Map<String, Object>> organization(String eventId, String id) {
        return rows(ORG_SELECT + " where o.event_id=:eventId and o.id=:id",
                Map.of("eventId", eventId, "id", id));
    }

    private static final String ORG_SELECT = """
            select o.id, o.group_name, o.leader_name, o.leader_birth, o.leader_ph_num,
              coalesce((select sum(r.contract_amount) from registration r where r.organization_id=o.id
                 and r.event_id=o.event_id and r.is_del=0),0) as contract_amount
            from organization o
            """;

    /** 금융 상단에 표시할 실제 주문 상태를 대상별 한 건씩 읽는다. 진행 중/불명, READY, 그 외 최신 순이다. */
    public List<Map<String, Object>> statuses(List<String> ids, boolean group) {
        if (ids.isEmpty()) { return List.of(); }
        String candidates = group ? """
                select p.organization_id as target_id, p.id, p.process_status, p.created_at
                from payment p where p.organization_id in (:ids)
                """ : """
                select p.registration_id as target_id, p.id, p.process_status, p.created_at
                from payment p where p.organization_id is null and p.registration_id in (:ids)
                union
                select a.registration_id as target_id, p.id, p.process_status, p.created_at
                from payment_allocation a join payment p on p.id=a.payment_id
                where p.organization_id is null and a.registration_id in (:ids)
                """;
        return rows("""
                select target_id, process_status from (
                  select q.*, row_number() over(partition by target_id order by
                    case process_status when 'UNKNOWN' then 0 when 'CONFIRMING' then 1
                      when 'READY' then 2 else 3 end, created_at desc, id desc) as rn
                  from (
                """ + candidates + ") q) ranked where rn=1", Map.of("ids", ids));
    }

    /** 대상 개인·단체의 모든 상태 주문 건수를 센다. */
    public long paymentCount(String targetId, boolean group) {
        return count("select count(*) from payment p where " + (group ? GROUP_SCOPE : PERSONAL_SCOPE), Map.of("targetId", targetId));
    }

    /** 실패·무효화 주문도 포함한 최신순 주문 페이지이다. 대상 소유권은 서비스에서 먼저 검증한다. */
    public List<Map<String, Object>> payments(String targetId, boolean group, int page, int size) {
        return rows("""
                select p.id, p.order_id, p.order_name, p.amount, p.purpose, p.process_status,
                       p.toss_status, p.payment_method, p.easy_pay_provider, p.created_at, p.approved_at
                from payment p where
                """ + (group ? GROUP_SCOPE : PERSONAL_SCOPE)
                + " order by p.created_at desc, p.id desc limit :limit offset :offset",
                paged(Map.of("targetId", targetId), page, size));
    }

    /** 결제 페이지 전체의 귀속을 읽는다. 신청 삭제 여부를 필터링하지 않고 누락 정보는 LEFT JOIN으로 보존한다. */
    public List<Map<String, Object>> allocations(String eventId, List<String> ids) {
        if (ids.isEmpty()) { return List.of(); }
        return rows("""
                select a.id, a.payment_id, a.registration_id, a.allocated_amount, a.allocation_purpose,
                       r.id as found_registration, r.name, r.is_del, r.organization_id
                from payment_allocation a left join registration r
                  on r.id=a.registration_id and r.event_id=:eventId
                where a.payment_id in (:ids) order by a.payment_id, a.id
                """, Map.of("eventId", eventId, "ids", ids));
    }

    /** 취소 귀속이 없어도 취소 부모 행 자체는 반환한다. */
    public List<Map<String, Object>> cancels(List<String> ids) {
        if (ids.isEmpty()) { return List.of(); }
        return rows("""
                select id, payment_id, cancel_type, purpose, cancel_amount, cancel_reason, status,
                       created_at, requested_at, canceled_at, error_code, error_message, refundable_amount_after_cancel
                from payment_cancel where payment_id in (:ids) order by created_at desc, id desc
                """, Map.of("ids", ids));
    }

    /** 취소 페이지의 전체 귀속을 일괄 조회하고 원귀속·참가자 누락을 숨기지 않는다. */
    public List<Map<String, Object>> cancelAllocations(String eventId, List<String> ids) {
        if (ids.isEmpty()) { return List.of(); }
        return rows("""
                select ca.id, ca.payment_cancel_id, ca.payment_allocation_id, ca.allocated_amount,
                       a.id as found_allocation, a.payment_id as original_payment_id, a.registration_id,
                       r.id as found_registration, r.name, r.is_del, r.organization_id
                from payment_cancel_allocation ca
                left join payment_allocation a on a.id=ca.payment_allocation_id
                left join registration r on r.id=a.registration_id and r.event_id=:eventId
                where ca.payment_cancel_id in (:ids) order by ca.payment_cancel_id, ca.id
                """, Map.of("eventId", eventId, "ids", ids));
    }

    /** 금융 주문의 대회 소속을 직접 대상과 귀속 경로로 확인한다. */
    public boolean paymentBelongsToEvent(String eventId, String paymentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from payment p where p.id=:paymentId and (
                  exists(select 1 from organization o where o.id=p.organization_id and o.event_id=:eventId)
                  or exists(select 1 from registration r where r.id=p.registration_id and r.event_id=:eventId)
                  or exists(select 1 from payment_allocation a join registration r on r.id=a.registration_id
                            where a.payment_id=p.id and r.event_id=:eventId)))
                """, Map.of("eventId", eventId, "paymentId", paymentId), Boolean.class));
    }

    private static final String LOG_SCOPE = """
            (l.payment_id=:paymentId or (l.payment_id is null and exists(
              select 1 from payment_cancel c where c.id=l.payment_cancel_id and c.payment_id=:paymentId)))
            """;

    /** 주문 로그와 취소 연결로만 남은 로그를 중복 없이 센다. */
    public long logCount(String paymentId) {
        return count("select count(*) from payment_process_log l where " + LOG_SCOPE, Map.of("paymentId", paymentId));
    }

    /** 개발용 ID·키 컬럼은 SELECT하지 않는다. 로그는 최신순으로 페이지 조회한다. */
    public List<Map<String, Object>> logs(String paymentId, int page, int size) {
        return rows("""
                select l.created_at, l.order_id, l.process_type, l.source, l.http_status,
                       l.error_code, l.error_message, l.metadata
                from payment_process_log l where
                """ + LOG_SCOPE + " order by l.created_at desc, l.id desc limit :limit offset :offset",
                paged(Map.of("paymentId", paymentId), page, size));
    }

    /** JDBC의 컬럼 프로젝션을 실행한다. */
    private List<Map<String, Object>> rows(String sql, Map<String, ?> params) {
        return jdbc.queryForList(sql, params);
    }

    /** COUNT는 SQL 집계 결과 그대로 반환한다. */
    private long count(String sql, Map<String, ?> params) {
        return jdbc.queryForObject(sql, params, Long.class);
    }

    /** 페이지 오프셋은 long으로 계산한다. */
    private Map<String, Object> paged(Map<String, ?> params, int page, int size) {
        Map<String, Object> result = new HashMap<>(params);
        result.put("limit", size);
        result.put("offset", (long) page * size);
        return result;
    }
}
