package kr.co.teambrain.marvelrun.admin.capacity.query.repository;

import kr.co.teambrain.marvelrun.admin.capacity.query.dto.CapacityParticipantResponse;
import kr.co.teambrain.marvelrun.admin.capacity.query.dto.CapacityParticipantState;
import kr.co.teambrain.marvelrun.admin.capacity.query.dto.CapacityQueryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/** 필요한 컬럼만 JDBC 프로젝션으로 읽는다. 엔티티 적재와 쓰기 잠금은 사용하지 않는다. */
@Repository
@RequiredArgsConstructor
public class AdminCapacityQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;

    private static final String PARTICIPANT_FROM = """
            from capacity c
            join reservation_item ri on ri.capacity_id = c.id
            join reservation rv on rv.id = ri.reservation_id
            join registration r on r.id = rv.registration_id and r.event_id = c.event_id
            left join organization o on o.id = r.organization_id and o.event_id = c.event_id
            where c.event_id = :eventId and c.id = :capacityId
              and rv.status in (:statuses) and ri.quantity > 0
            """;

    /** 존재하지 않는 대회와 정원이 아직 없는 대회를 구분한다. */
    public boolean eventExists(String eventId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from event where id = :eventId)",
                Map.of("eventId", eventId), Boolean.class));
    }

    /** 비활성 정원도 포함하여 저장된 한도·확정·홀딩 카운트를 한 번에 반환한다. */
    public List<CapacityQueryResponse> capacities(String eventId) {
        return jdbc.query("""
                select id, type, name, resource_key, souvenir_id, size,
                       limit_count, confirmed_count, held_count, active
                from capacity where event_id = :eventId
                order by type, resource_key, id
                """, Map.of("eventId", eventId), (rs, rowNum) -> new CapacityQueryResponse(
                rs.getString("id"), rs.getString("type"), rs.getString("name"),
                rs.getString("resource_key"), rs.getString("souvenir_id"), rs.getString("size"),
                rs.getInt("limit_count"), rs.getInt("confirmed_count"), rs.getInt("held_count"),
                rs.getBoolean("active")));
    }

    /** 요청한 정원이 해당 대회에 속하는지 컬럼 조회로 검증한다. */
    public boolean capacityExists(String eventId, String capacityId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from capacity where event_id = :eventId and id = :capacityId)
                """, Map.of("eventId", eventId, "capacityId", capacityId), Boolean.class));
    }

    /** 동일 조건의 전체 참가자 수를 조회한다. 수량 합계가 아니라 사람 수이다. */
    public long participantCount(String eventId, String capacityId, CapacityParticipantState state) {
        return jdbc.queryForObject("select count(*) " + PARTICIPANT_FROM,
                parameters(eventId, capacityId, state), Long.class);
    }

    /** 정원을 실제 점유 중인 참가자를 ID 순서로 페이지 조회한다. 신청 삭제 여부로 점유를 숨기지 않는다. */
    public List<CapacityParticipantResponse> participants(
            String eventId, String capacityId, CapacityParticipantState state, int page, int size) {
        MapSqlParameterSource params = parameters(eventId, capacityId, state)
                .addValue("limit", size).addValue("offset", (long) page * size);
        return jdbc.query("""
                select r.id, r.name, r.birth, r.ph_num, o.group_name as organization_name
                """ + PARTICIPANT_FROM + " order by r.id limit :limit offset :offset", params,
                (rs, rowNum) -> new CapacityParticipantResponse(rs.getString("id"),
                        rs.getString("name"), rs.getString("birth"), rs.getString("ph_num"),
                        rs.getString("organization_name")));
    }

    /** 목록과 COUNT에 동일한 대회·정원·예약 상태 조건을 적용한다. */
    private MapSqlParameterSource parameters(String eventId, String capacityId, CapacityParticipantState state) {
        return new MapSqlParameterSource("eventId", eventId)
                .addValue("capacityId", capacityId).addValue("statuses", state.reservationStatuses());
    }
}
