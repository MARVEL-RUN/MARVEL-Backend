package kr.co.teambrain.marvelrun.admin.event.query.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 버추얼런 인쇄·패킹 명단의 원본 행을 JDBC로 읽는다(읽기 전용).
 * 대상은 신청 시각이 [from, to)에 드는 미삭제 확정 신청이며 외부 결제 신청도 포함한다. from·to가 null이면 그쪽 끝을 두지 않는다.
 * 상향 결제를 기다리는 신청(ADDITIONAL_PAYMENT_REQUIRED)은 확정 상태가 아니므로 넣지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class VirtualRosterQueryRepository {
    private static final String ROUND_FILTER = """
            WHERE r.event_id = :eventId AND r.is_del = 0 AND r.status = 'CONFIRMED'
              AND (:from IS NULL OR r.registration_date >= :from) AND (:to IS NULL OR r.registration_date < :to)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /** 대회 이름과 형식이다. */
    public record EventInfo(String name, String format) { }

    /** 인쇄용 참가 항목 한 건이다. 종목명은 거리 접두어를 떼기 전 값이다. */
    public record PrintEntry(String bib, String name, String categoryName) { }

    /**
     * 패킹용 한 행이며 참가 항목 하나다. 상품·기념품은 그 항목이 속한 구매 줄 값이다.
     * 구매 줄이 없는 신청은 itemId가 null이고 상품·기념품은 신청 행 값이다.
     * 참가 항목이 없는 구매 줄도 한 행이며 이때 항목 값(entryCategoryName·bib·entryName)은 null이다.
     */
    public record PackingLine(String registrationId, String itemId, String productName, String productType,
            String souvenirJson, String entryCategoryName, String bib, String entryName,
            String receiver, String phone, String address, String addressDetail) { }

    public Optional<EventInfo> findEvent(String eventId) {
        return jdbc.query("SELECT name_kr, event_format FROM event WHERE id = :eventId",
                new MapSqlParameterSource("eventId", eventId),
                (rs, rowNum) -> new EventInfo(rs.getString("name_kr"), rs.getString("event_format"))).stream().findFirst();
    }

    /** 참가 항목 하나가 한 행이다. 배번 길이, 배번 순이고 배번이 없는 항목은 끝에 둔다. */
    public List<PrintEntry> findPrintEntries(String eventId, LocalDateTime from, LocalDateTime to) {
        return jdbc.query("""
                SELECT e.bib_number, e.name, c.name AS category_name
                FROM registration_entry e
                JOIN registration r ON r.id = e.registration_id
                JOIN event_category c ON c.id = e.event_category_id
                """ + ROUND_FILTER + """
                ORDER BY (e.bib_number IS NULL), CHAR_LENGTH(e.bib_number), e.bib_number, r.registration_date, r.id, c.sort_order, e.id
                """, range(eventId, from, to),
                (rs, rowNum) -> new PrintEntry(rs.getString("bib_number"), rs.getString("name"), rs.getString("category_name")));
    }

    /** 참가 항목 하나가 한 행이다. 신청일시, 신청 ID, 구매 줄 순서, 종목 순서, 항목 ID 순이다. */
    public List<PackingLine> findPackingLines(String eventId, LocalDateTime from, LocalDateTime to) {
        return jdbc.query("""
                SELECT r.id, i.id AS item_id, pc.name AS product_name, pc.product_type,
                       CASE WHEN i.id IS NULL THEN r.souvenir_json ELSE i.souvenir_json END AS souvenir_json,
                       ec.name AS entry_category_name, e.bib_number, e.name AS entry_name,
                       r.name, r.ph_num, r.address, r.address_detail
                FROM registration r
                LEFT JOIN registration_item i ON i.registration_id = r.id
                JOIN event_category pc ON pc.id = COALESCE(i.event_category_id, r.event_category_id)
                LEFT JOIN registration_entry e ON e.registration_item_id = i.id
                LEFT JOIN event_category ec ON ec.id = e.event_category_id
                """ + ROUND_FILTER + """
                ORDER BY r.registration_date, r.id, i.sort_order, ec.sort_order, e.id
                """, range(eventId, from, to),
                (rs, rowNum) -> new PackingLine(rs.getString("id"), rs.getString("item_id"),
                        rs.getString("product_name"), rs.getString("product_type"), rs.getString("souvenir_json"),
                        rs.getString("entry_category_name"), rs.getString("bib_number"), rs.getString("entry_name"),
                        rs.getString("name"), rs.getString("ph_num"), rs.getString("address"), rs.getString("address_detail")));
    }

    /** null 시각도 IS NULL 비교가 되도록 자료형을 함께 넘긴다. */
    private MapSqlParameterSource range(String eventId, LocalDateTime from, LocalDateTime to) {
        return new MapSqlParameterSource("eventId", eventId)
                .addValue("from", from, Types.TIMESTAMP).addValue("to", to, Types.TIMESTAMP);
    }
}
