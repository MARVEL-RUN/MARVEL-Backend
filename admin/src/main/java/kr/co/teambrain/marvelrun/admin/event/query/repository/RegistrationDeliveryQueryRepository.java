package kr.co.teambrain.marvelrun.admin.event.query.repository;

import kr.co.teambrain.marvelrun.admin.event.query.dto.report.RegistrationDeliveryReportModels.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/** 배송 조회에 필요한 스칼라 자료를 배치 조회한다. 상태 변경·잠금·PG 통신은 수행하지 않는다. */
@Repository
public class RegistrationDeliveryQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;

    /** 기존 관리자 데이터소스의 JDBC 조회기를 사용한다. */
    public RegistrationDeliveryQueryRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    // 최초 참가비 귀속을 전 기간에서 결정하고 나서 기간을 적용한다. 누락 승인일은 임의 대체하지 않는다.
    private static final String FIRST_APPROVAL = """
        with eligible as (
            select id from registration where event_id=:eventId and is_del=false
              and status in ('CONFIRMED','ADDITIONAL_PAYMENT_REQUIRED','PARTIAL_REFUND_REQUIRED')
        ), initial_sources as (
            select a.registration_id, p.approved_at
            from payment_allocation a join payment p on p.id=a.payment_id
            join eligible e on e.id=a.registration_id
            where p.process_status='COMPLETED'
              and coalesce(a.allocation_purpose, case when p.purpose<>'MIXED_PAYMENT' then p.purpose end)='REGISTRATION_TRY'
            union all
            select p.registration_id, p.approved_at from payment p join eligible e on e.id=p.registration_id
            where p.process_status='COMPLETED' and p.purpose='REGISTRATION_TRY'
              and not exists (select 1 from payment_allocation a where a.payment_id=p.id)
        ), first_approval as (
            select registration_id,
                case when count(*)=count(approved_at) then min(approved_at) else null end as approved_at
            from initial_sources group by registration_id
        )
        """;

    /** 상단에 사용할 대회 정보를 조회한다. */
    public Optional<EventInfo> findDeliveryEvent(String eventId) {
        // 대회 존재 확인은 첫 배치 전에 수행한다.
        return jdbc.query("select id,name_kr,start_date from event where id=:id", Map.of("id", eventId),
                (rs, index) -> new EventInfo(rs.getString("id"), rs.getString("name_kr"), time(rs, "start_date")))
                .stream().findFirst();
    }

    /** 최초 승인일·ID 커서로 조회하며 승인일 미확인 신청은 마지막에 포함한다. */
    public List<Candidate> findDeliveryCandidates(String eventId, LocalDateTime startUtc, LocalDateTime endUtc,
            Candidate cursor, int size) {
        // NULL 승인일도 별도 확인 대상으로 포함하되 제외 상태는 eligible에서 제거한다.
        String after = cursor == null ? "" : cursor.firstApprovedUtc() == null
                ? " and f.approved_at is null and r.id>:cursorId "
                : " and (f.approved_at>:cursorAt or (f.approved_at=:cursorAt and r.id>:cursorId) or f.approved_at is null) ";
        String sql = FIRST_APPROVAL + """
            select r.id,r.status,r.is_del,r.name,r.birth,r.ph_num,r.organization_id,o.group_name,
                r.event_category_id,c.name category_name,r.souvenir_json,
                case when r.organization_id is null then r.address else o.address end address,
                case when r.organization_id is null then r.address_detail else o.address_detail end address_detail,
                r.registration_date,f.approved_at,r.contract_amount,r.paid_amount
            from eligible e join registration r on r.id=e.id
            left join first_approval f on f.registration_id=r.id
            left join organization o on o.id=r.organization_id and o.event_id=r.event_id
            left join event_category c on c.id=r.event_category_id and c.event_id=r.event_id
            where (f.approved_at is null or (f.approved_at>=:startUtc and f.approved_at<:endUtc))
            """ + after + " order by f.approved_at is null,f.approved_at,r.id limit :size";
        MapSqlParameterSource params = new MapSqlParameterSource("eventId", eventId)
                .addValue("startUtc", startUtc).addValue("endUtc", endUtc).addValue("size", size)
                .addValue("cursorId", cursor == null ? null : cursor.id())
                .addValue("cursorAt", cursor == null ? null : cursor.firstApprovedUtc());
        return jdbc.query(sql, params, (rs, index) -> new Candidate(rs.getString("id"), rs.getString("status"),
                rs.getBoolean("is_del"), rs.getString("name"), rs.getString("birth"), rs.getString("ph_num"),
                rs.getString("organization_id"), rs.getString("group_name"), rs.getString("event_category_id"),
                rs.getString("category_name"), rs.getString("souvenir_json"), rs.getString("address"),
                rs.getString("address_detail"), time(rs,"registration_date"),time(rs,"approved_at"),
                rs.getBigDecimal("contract_amount"),rs.getBigDecimal("paid_amount")));
    }

    /** 참가자별 금융 귀속을 읽는다. 환불 귀속 없는 단체 취소는 추정하지 않고 미확정으로 표시한다. */
    public Map<String, List<PaymentFact>> findPaymentFacts(List<String> ids) {
        if (ids.isEmpty()) { return Map.of(); }
        // 정상 Allocation과 Allocation 자체가 없는 개인 직접 원장을 중복 없이 합친다.
        String sql = """
            with sources as (
                select a.registration_id,a.id allocation_id,p.id payment_id,a.allocated_amount amount,
                    coalesce(a.allocation_purpose,case when p.purpose<>'MIXED_PAYMENT' then p.purpose end) purpose
                from payment_allocation a join payment p on p.id=a.payment_id where a.registration_id in (:ids)
                union all
                select p.registration_id,null,p.id,p.amount,p.purpose from payment p
                where p.registration_id in (:ids)
                  and not exists(select 1 from payment_allocation a where a.payment_id=p.id)
            )
            select s.*,p.process_status,p.approved_at,p.toss_status,
                coalesce((select sum(ca.allocated_amount) from payment_cancel_allocation ca
                    join payment_cancel pc on pc.id=ca.payment_cancel_id
                    where ca.payment_allocation_id=s.allocation_id and pc.status='DONE'),0)
                + coalesce((select sum(pc.cancel_amount) from payment_cancel pc
                    where pc.payment_id=p.id and pc.status='DONE' and p.registration_id=s.registration_id
                      and not exists(select 1 from payment_cancel_allocation ca where ca.payment_cancel_id=pc.id)),0) refunded,
                exists(select 1 from payment_cancel pc where pc.payment_id=p.id
                    and (pc.status in ('PROCESSING','UNKNOWN') or pc.status is null)
                    and (exists(select 1 from payment_cancel_allocation ca
                        where ca.payment_cancel_id=pc.id and ca.payment_allocation_id=s.allocation_id)
                        or not exists(select 1 from payment_cancel_allocation ca where ca.payment_cancel_id=pc.id))) unsettled,
                exists(select 1 from payment_cancel pc where pc.payment_id=p.id
                    and pc.status in ('DONE','PROCESSING','UNKNOWN')
                    and p.registration_id is null
                    and not exists(select 1 from payment_cancel_allocation ca where ca.payment_cancel_id=pc.id)) missing
            from sources s join payment p on p.id=s.payment_id
            order by s.registration_id,p.id
            """;
        Map<String, List<PaymentFact>> result = new HashMap<>();
        jdbc.query(sql, Map.of("ids", ids), (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            PaymentFact fact = new PaymentFact(rs.getString("registration_id"),rs.getString("payment_id"),
                    rs.getString("purpose"),rs.getString("process_status"),time(rs,"approved_at"),
                    rs.getBigDecimal("amount"),rs.getBigDecimal("refunded"),rs.getBoolean("unsettled"),
                    rs.getBoolean("missing"),rs.getString("toss_status"));
            result.computeIfAbsent(fact.registrationId(), ignored -> new ArrayList<>()).add(fact);
        });
        return result;
    }

    /** 불명확 신청의 환불만 읽으며 단체 귀속이 없으면 전체 금액을 개인 금액으로 대체하지 않는다. */
    public Map<String,List<RefundFact>> findUnclearRefundHistory(List<String> ids) {
        if (ids.isEmpty()) { return Map.of(); }

        // 배분된 금액을 우선하고 배분 자체가 없는 직접 개인 환불만 전체 금액을 사용한다.
        String sql = """
            with linked_payments as (
                select distinct a.registration_id,p.id payment_id
                from payment_allocation a join payment p on p.id=a.payment_id
                where a.registration_id in (:ids)
                union
                select p.registration_id,p.id from payment p
                where p.registration_id in (:ids)
                  and not exists(select 1 from payment_allocation a where a.payment_id=p.id)
            ), attributed as (
                select ca.payment_cancel_id,a.registration_id,sum(ca.allocated_amount) amount
                from payment_cancel_allocation ca
                join payment_allocation a on a.id=ca.payment_allocation_id
                where a.registration_id in (:ids)
                group by ca.payment_cancel_id,a.registration_id
            )
            select l.registration_id,pc.id,pc.purpose,pc.status,pc.requested_at,pc.canceled_at,
                case when a.registration_id is not null then a.amount
                     when p.registration_id=l.registration_id then pc.cancel_amount
                     else null end participant_amount
            from linked_payments l join payment p on p.id=l.payment_id
            join payment_cancel pc on pc.payment_id=p.id
            left join attributed a on a.payment_cancel_id=pc.id and a.registration_id=l.registration_id
            where a.registration_id is not null
               or not exists(select 1 from payment_cancel_allocation ca where ca.payment_cancel_id=pc.id)
            order by l.registration_id,pc.id
            """;
        Map<String,List<RefundFact>> result = new HashMap<>();
        jdbc.query(sql,Map.of("ids",ids),(org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            RefundFact fact = new RefundFact(rs.getString("registration_id"),rs.getString("id"),
                    rs.getString("purpose"),rs.getString("status"),rs.getBigDecimal("participant_amount"),
                    time(rs,"requested_at"),time(rs,"canceled_at"));
            result.computeIfAbsent(fact.registrationId(),ignored -> new ArrayList<>()).add(fact);
        });
        return result;
    }

    /** 현재 예약 상세 또는 불명확 대상의 이력을 한 배치로 읽는다. 정상 대상에는 history를 요청하지 않는다. */
    public Map<String, List<ReservationFact>> findReservations(List<String> ids, boolean includeHistory) {
        if (ids.isEmpty()) { return Map.of(); }
        // 상세 행 때문에 history JSON이 반복 전송되지 않도록 두 집합을 별도로 조회한다.
        Map<String,List<CapacityItem>> items = new HashMap<>();
        jdbc.query("select i.reservation_id,i.capacity_id,i.quantity from reservation_item i "
                + "join reservation r on r.id=i.reservation_id where r.registration_id in (:ids)", Map.of("ids", ids),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> items.computeIfAbsent(rs.getString("reservation_id"),
                        ignored -> new ArrayList<>()).add(new CapacityItem(rs.getString("capacity_id"),rs.getInt("quantity"))));
        Map<String,List<ReservationFact>> result = new HashMap<>();
        jdbc.query("select id,registration_id,status," + (includeHistory ? "history" : "null as history")
                + " from reservation where registration_id in (:ids) order by id", Map.of("ids",ids),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    ReservationFact fact = new ReservationFact(rs.getString("registration_id"),rs.getString("id"),
                            rs.getString("status"),rs.getString("history"),List.copyOf(items.getOrDefault(rs.getString("id"),List.of())));
                    result.computeIfAbsent(fact.registrationId(), ignored -> new ArrayList<>()).add(fact);
                });
        return result;
    }

    /** 과거·현재 자원 ID를 대회 범위 안에서 해석한다. 종목 연결은 복수 후보를 보존한다. */
    public Map<String, CapacityInfo> findCapacities(String eventId, Collection<String> ids) {
        if (ids.isEmpty()) { return Map.of(); }
        Map<String,CapacityInfo> result = new HashMap<>();
        jdbc.query("""
            select c.id,c.type,c.souvenir_id,s.name souvenir_name,c.size,ec.id category_id,ec.name category_name
            from capacity c left join souvenir s on s.id=c.souvenir_id and s.event_id=c.event_id
            left join capacity_category cc on cc.capacity_id=c.id
            left join event_category ec on ec.id=cc.event_category_id and ec.event_id=c.event_id
            where c.event_id=:eventId and c.id in (:ids) order by c.id,ec.id
            """, new MapSqlParameterSource("eventId",eventId).addValue("ids",ids),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    String id = rs.getString("id");
                    CapacityInfo current = result.get(id);
                    List<String> categoryIds = current == null ? new ArrayList<>() : new ArrayList<>(current.categoryIds());
                    List<String> names = current == null ? new ArrayList<>() : new ArrayList<>(current.categoryNames());
                    if (rs.getString("category_id") != null) {
                        categoryIds.add(rs.getString("category_id")); names.add(rs.getString("category_name"));
                    }
                    result.put(id,new CapacityInfo(id,rs.getString("type"),rs.getString("souvenir_id"),
                            rs.getString("souvenir_name"),rs.getString("size"),List.copyOf(categoryIds),List.copyOf(names)));
                });
        return result;
    }

    /** 선택 기념품의 현재 이름을 대회 범위에서 한 번에 조회한다. */
    public Map<String,String> findSouvenirNames(String eventId, Collection<String> ids) {
        if (ids.isEmpty()) { return Map.of(); }
        Map<String,String> result = new HashMap<>();
        jdbc.query("select id,name from souvenir where event_id=:eventId and id in (:ids)",
                new MapSqlParameterSource("eventId",eventId).addValue("ids",ids),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.put(rs.getString("id"),rs.getString("name")));
        return result;
    }

    /** JDBC의 nullable 시간 값을 오프셋 없는 저장값 그대로 반환한다. */
    private static LocalDateTime time(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }
}
