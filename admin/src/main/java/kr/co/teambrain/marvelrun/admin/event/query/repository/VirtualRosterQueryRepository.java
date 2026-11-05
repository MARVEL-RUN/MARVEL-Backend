package kr.co.teambrain.marvelrun.admin.event.query.repository;

import kr.co.teambrain.marvelrun.admin.event.query.dto.report.VirtualRosterModels.Course;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 버추얼런 인쇄·패킹 명단의 원본 행을 JDBC로 읽는다(읽기 전용).
 * 대상은 차수 구간 [startAt, endAt)에 신청한 미삭제 확정 신청이며 외부 결제 신청도 포함한다.
 */
@Repository
@RequiredArgsConstructor
public class VirtualRosterQueryRepository {
    private static final String ROUND_FILTER = """
            WHERE r.event_id = :eventId AND r.is_del = 0 AND r.status = 'CONFIRMED'
              AND r.registration_date >= :startAt AND r.registration_date < :endAt
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /** 대회 이름과 형식이다. */
    public record EventInfo(String name, String format) { }

    /** 인쇄용 참가 항목 한 건이다. 종목명은 거리 접두어를 떼기 전 값이다. */
    public record PrintEntry(String bib, String name, String categoryName) { }

    /** 패킹용 신청 한 건이다. */
    public record PackingRegistration(String id, String categoryName, String productType, String receiver, String phone,
            String address, String addressDetail, String souvenirJson) { }

    /** 패킹용 참가 항목 한 건이다. */
    public record PackingEntry(String registrationId, String categoryId, String bib, String name) { }

    public Optional<EventInfo> findEvent(String eventId) {
        return jdbc.query("SELECT name_kr, event_format FROM event WHERE id = :eventId",
                new MapSqlParameterSource("eventId", eventId),
                (rs, rowNum) -> new EventInfo(rs.getString("name_kr"), rs.getString("event_format"))).stream().findFirst();
    }

    /** 단품 종목(패키지 하위 포함)을 정렬 순서대로 읽는다. 패킹 명단의 배번·이름 열이 된다. */
    public List<Course> findChildCourses(String eventId) {
        return jdbc.query("""
                SELECT id, name FROM event_category
                WHERE event_id = :eventId AND (product_type = 'SINGLE' OR parent_category_id IS NOT NULL)
                ORDER BY sort_order, id
                """, new MapSqlParameterSource("eventId", eventId),
                (rs, rowNum) -> new Course(rs.getString("id"), rs.getString("name")));
    }

    /** 참가 항목 하나가 한 행이다. 배번 길이, 배번 순이고 배번이 없는 항목은 끝에 둔다. */
    public List<PrintEntry> findPrintEntries(String eventId, LocalDateTime startAt, LocalDateTime endAt) {
        return jdbc.query("""
                SELECT e.bib_number, e.name, c.name AS category_name
                FROM registration_entry e
                JOIN registration r ON r.id = e.registration_id
                JOIN event_category c ON c.id = e.event_category_id
                """ + ROUND_FILTER + """
                ORDER BY (e.bib_number IS NULL), CHAR_LENGTH(e.bib_number), e.bib_number, r.registration_date, r.id, c.sort_order, e.id
                """, range(eventId, startAt, endAt),
                (rs, rowNum) -> new PrintEntry(rs.getString("bib_number"), rs.getString("name"), rs.getString("category_name")));
    }

    /** 신청 하나가 한 행이다. 신청일시, 신청 ID 순이다. */
    public List<PackingRegistration> findPackingRegistrations(String eventId, LocalDateTime startAt, LocalDateTime endAt) {
        return jdbc.query("""
                SELECT r.id, c.name AS category_name, c.product_type, r.name, r.ph_num, r.address, r.address_detail, r.souvenir_json
                FROM registration r
                JOIN event_category c ON c.id = r.event_category_id
                """ + ROUND_FILTER + """
                ORDER BY r.registration_date, r.id
                """, range(eventId, startAt, endAt),
                (rs, rowNum) -> new PackingRegistration(rs.getString("id"), rs.getString("category_name"),
                        rs.getString("product_type"), rs.getString("name"), rs.getString("ph_num"),
                        rs.getString("address"), rs.getString("address_detail"), rs.getString("souvenir_json")));
    }

    /** 신청 ID 묶음의 참가 항목을 읽는다. */
    public List<PackingEntry> findPackingEntries(Collection<String> registrationIds) {
        if (registrationIds.isEmpty()) { return new ArrayList<>(); }
        return jdbc.query("""
                SELECT registration_id, event_category_id, bib_number, name
                FROM registration_entry WHERE registration_id IN (:ids)
                """, new MapSqlParameterSource("ids", registrationIds),
                (rs, rowNum) -> new PackingEntry(rs.getString("registration_id"), rs.getString("event_category_id"),
                        rs.getString("bib_number"), rs.getString("name")));
    }

    private MapSqlParameterSource range(String eventId, LocalDateTime startAt, LocalDateTime endAt) {
        return new MapSqlParameterSource("eventId", eventId).addValue("startAt", startAt).addValue("endAt", endAt);
    }
}
