package kr.co.teambrain.marvelrun.admin.event.command.repository;

import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배번 발급 대상 조회와 번호 기록을 JDBC로 처리한다.
 * 호출자의 트랜잭션(대회 잠금 이후)에 참여하며, 어느 한 건이라도 실패하면 전체가 롤백된다.
 */
@Repository
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class BibIssueRepository {
    private static final String HOMEPAGE = "HOMEPAGE";
    private static final int BATCH_SIZE = 100;

    private final NamedParameterJdbcTemplate jdbc;

    /** 마감 전에 신청했고 아직 대기 중인 패키지 상향 건수다. */
    public int countPendingUpgrades(String eventId, LocalDateTime deadline) {
        return count("""
                SELECT COUNT(*) FROM package_upgrade u JOIN registration r ON r.id = u.registration_id
                WHERE u.event_id = :eventId AND u.status = 'PENDING' AND r.registration_date < :deadline
                """, eventId, deadline);
    }

    /** 홈페이지 채널의 종목별 배번 순번 행을 잠그고 읽는다. */
    public Map<String, Sequence> lockHomepageSequences(String eventId) {
        Map<String, Sequence> sequences = new LinkedHashMap<>();
        jdbc.query("""
                SELECT event_category_id, start_number, last_number FROM bib_sequence
                WHERE event_id = :eventId AND channel = :channel
                ORDER BY event_category_id FOR UPDATE
                """, new MapSqlParameterSource("eventId", eventId).addValue("channel", HOMEPAGE), rs -> {
            int start = rs.getInt("start_number");
            int last = rs.getInt("last_number");
            Integer lastNumber = rs.wasNull() ? null : last;
            sequences.put(rs.getString("event_category_id"), new Sequence(start, lastNumber));
        });
        return sequences;
    }

    /** 배번이 없는 발급 대상 참가 항목을 신청일시·신청 ID·종목 순서·항목 ID 순으로 읽는다. */
    public List<Target> findTargets(String eventId, LocalDateTime deadline) {
        return jdbc.query("""
                SELECT e.id AS entry_id, e.event_category_id, c.name AS category_name, c.sort_order
                FROM registration_entry e
                JOIN registration r ON r.id = e.registration_id
                JOIN event_category c ON c.id = e.event_category_id
                WHERE r.event_id = :eventId AND r.is_del = 0 AND r.status = 'CONFIRMED' AND r.external_payment = 0
                  AND e.bib_number IS NULL AND r.registration_date < :deadline
                ORDER BY r.registration_date, r.id, c.sort_order, e.id
                """, new MapSqlParameterSource("eventId", eventId).addValue("deadline", deadline),
                (rs, rowNum) -> new Target(rs.getString("entry_id"), rs.getString("event_category_id"),
                        rs.getString("category_name"), rs.getLong("sort_order")));
    }

    /** 발급 범위(확정·미삭제·일반 결제·마감 전)에서 이미 배번을 받은 참가 항목 수다. */
    public int countAlreadyIssued(String eventId, LocalDateTime deadline) {
        return count("""
                SELECT COUNT(*) FROM registration_entry e JOIN registration r ON r.id = e.registration_id
                WHERE r.event_id = :eventId AND r.is_del = 0 AND r.status = 'CONFIRMED' AND r.external_payment = 0
                  AND e.bib_number IS NOT NULL AND r.registration_date < :deadline
                """, eventId, deadline);
    }

    /** 외부 결제라 이번 발급에서 제외한 확정 신청 수다. */
    public int countExternalPaymentSkipped(String eventId, LocalDateTime deadline) {
        return count("""
                SELECT COUNT(*) FROM registration r
                WHERE r.event_id = :eventId AND r.is_del = 0 AND r.status = 'CONFIRMED' AND r.external_payment = 1
                  AND r.registration_date < :deadline
                """, eventId, deadline);
    }

    /** 상품 종목인데 참가 항목이 하나도 없는 확정 신청 수다(발급하지 않고 건수만 센다). */
    public int countRegistrationsWithoutEntries(String eventId, LocalDateTime deadline) {
        return count("""
                SELECT COUNT(*) FROM registration r JOIN event_category c ON c.id = r.event_category_id
                WHERE r.event_id = :eventId AND r.is_del = 0 AND r.status = 'CONFIRMED' AND r.registration_date < :deadline
                  AND c.product_type IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM registration_entry e WHERE e.registration_id = r.id)
                """, eventId, deadline);
    }

    /** 참가 항목에 배번을 쓴다. 이미 배번이 있는 항목은 갱신하지 않으며 그 경우 전체를 롤백한다. */
    public void updateBibs(List<Assignment> assignments, LocalDateTime issuedAt) {
        for (int start = 0; start < assignments.size(); start += BATCH_SIZE) {
            SqlParameterSource[] parameters = assignments.subList(start, Math.min(start + BATCH_SIZE, assignments.size()))
                    .stream().map(assignment -> new MapSqlParameterSource()
                            .addValue("entryId", assignment.entryId())
                            .addValue("bib", assignment.bibNumber())
                            .addValue("issuedAt", issuedAt))
                    .toArray(SqlParameterSource[]::new);
            validateUpdatedRows(jdbc.batchUpdate("""
                    UPDATE registration_entry SET bib_number = :bib, bib_issued_at = :issuedAt
                    WHERE id = :entryId AND bib_number IS NULL
                    """, parameters));
        }
    }

    /** 종목별로 마지막으로 준 번호를 기록한다. */
    public void updateLastNumbers(String eventId, Map<String, Integer> lastNumbers) {
        SqlParameterSource[] parameters = lastNumbers.entrySet().stream()
                .map(entry -> new MapSqlParameterSource()
                        .addValue("eventId", eventId)
                        .addValue("categoryId", entry.getKey())
                        .addValue("channel", HOMEPAGE)
                        .addValue("lastNumber", entry.getValue()))
                .toArray(SqlParameterSource[]::new);
        validateUpdatedRows(jdbc.batchUpdate("""
                UPDATE bib_sequence SET last_number = :lastNumber
                WHERE event_id = :eventId AND event_category_id = :categoryId AND channel = :channel
                """, parameters));
    }

    private int count(String sql, String eventId, LocalDateTime deadline) {
        Integer value = jdbc.queryForObject(sql,
                new MapSqlParameterSource("eventId", eventId).addValue("deadline", deadline), Integer.class);
        return value == null ? 0 : value;
    }

    /** JDBC 드라이버가 건수를 생략한 성공은 허용하고 대상 누락 또는 다중 갱신은 거절한다. */
    private void validateUpdatedRows(int[] counts) {
        for (int count : counts) {
            if (count != 1 && count != Statement.SUCCESS_NO_INFO) {
                throw new CustomException(ErrorCode.CONCURRENT_MODIFICATION);
            }
        }
    }

    /** lastNumber가 null이면 아직 한 번도 발급하지 않은 순번이다. */
    public record Sequence(int startNumber, Integer lastNumber) {
        public int next() { return lastNumber == null ? startNumber : lastNumber + 1; }
    }

    /** 발급 대상 참가 항목과 종목 정보다. */
    public record Target(String entryId, String categoryId, String categoryName, long sortOrder) { }

    /** 참가 항목에 줄 배번이다. */
    public record Assignment(String entryId, String bibNumber) { }
}
