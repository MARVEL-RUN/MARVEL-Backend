package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 두 MVP 테스트 지원 클래스가 만든 대회 한 건의 종속 데이터를 같은 트랜잭션으로 정리한다. */
final class CapacityMvpFixtureCleaner {
    /** 정리용 정적 진입점만 제공한다. */
    private CapacityMvpFixtureCleaner() { }

    /** 현재 테스트가 생성한 UUID만 정리하며 실패는 숨기지 않고 테스트 실패로 전달한다. */
    static void deleteCreatedEventFixtures(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                          String eventId) {
        // 준비 단계에서 대회 생성에 도달하지 못한 경우 정리할 식별자가 없다.
        if (eventId == null) {
            return;
        }

        // 업무 트랜잭션의 rollback-only 상태에 정리 작업이 묶이지 않게 독립 트랜잭션을 연다.
        TransactionTemplate cleanupTransaction = new TransactionTemplate(transactionManager);
        cleanupTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        cleanupTransaction.executeWithoutResult(status -> {
            // 이름만 검색해 전체 대회를 지우지 않고 이번 테스트의 UUID와 생성 표식을 함께 확인한다.
            Integer targetCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM event WHERE id = ? AND name_kr = 'MVP 통합 테스트' "
                            + "AND region = '테스트' AND host = '테스트' AND organizer = '테스트'",
                    Integer.class, eventId);
            if (targetCount == null || targetCount == 0) {
                Integer existingCount = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM event WHERE id = ?", Integer.class, eventId);
                if (existingCount != null && existingCount != 0) {
                    throw new AssertionError("MVP fixture 표식이 다른 대회는 정리할 수 없습니다: " + eventId);
                }
                return;
            }

            // 선택 기능의 이행 전후 DB를 지원하되 존재하는 테이블의 SQL 오류는 무시하지 않는다.
            Set<String> tables = new HashSet<>(jdbc.queryForList(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                    String.class));
            NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(jdbc);
            Map<String, String> parameters = Map.of("eventId", eventId);
            for (Deletion deletion : deletions()) {
                if (tables.contains(deletion.table())) {
                    namedJdbc.update(deletion.sql(), parameters);
                }
            }

            // 부모 삭제 실패나 정리 누락을 성공으로 처리하지 않는다.
            Integer remaining = jdbc.queryForObject("SELECT COUNT(*) FROM event WHERE id = ?", Integer.class, eventId);
            if (remaining == null || remaining != 0) {
                throw new AssertionError("MVP fixture 대회 정리가 완료되지 않았습니다: " + eventId);
            }
        });
    }

    /** FK가 없는 결제 로그까지 포함하여 자식에서 부모 순서로 정리한다. FK 검사를 끄지 않는다. */
    private static List<Deletion> deletions() {
        return List.of(
                new Deletion("payment_process_log",
                        """
                        DELETE FROM payment_process_log WHERE payment_id IN (SELECT id FROM payment WHERE registration_id IN
                        (SELECT id FROM registration WHERE event_id = :eventId) OR organization_id IN (SELECT id FROM
                        organization WHERE event_id = :eventId)) OR registration_id IN (SELECT id FROM registration WHERE
                        event_id = :eventId) OR payment_cancel_id IN (SELECT id FROM payment_cancel WHERE payment_id IN
                        (SELECT id FROM payment WHERE registration_id IN (SELECT id FROM registration WHERE event_id =
                        :eventId) OR organization_id IN (SELECT id FROM organization WHERE event_id = :eventId)))
                        """),
                new Deletion("payment_cancel_allocation",
                        """
                        DELETE FROM payment_cancel_allocation WHERE payment_cancel_id IN (SELECT id FROM payment_cancel WHERE
                        payment_id IN (SELECT id FROM payment WHERE registration_id IN (SELECT id FROM registration WHERE
                        event_id = :eventId) OR organization_id IN (SELECT id FROM organization WHERE event_id = :eventId)))
                        OR payment_allocation_id IN (SELECT id FROM payment_allocation WHERE payment_id IN (SELECT id FROM
                        payment WHERE registration_id IN (SELECT id FROM registration WHERE event_id = :eventId) OR
                        organization_id IN (SELECT id FROM organization WHERE event_id = :eventId)))
                        """),
                new Deletion("payment_cancel",
                        """
                        DELETE FROM payment_cancel WHERE payment_id IN (SELECT id FROM payment WHERE registration_id IN
                        (SELECT id FROM registration WHERE event_id = :eventId) OR organization_id IN (SELECT id FROM
                        organization WHERE event_id = :eventId))
                        """),
                new Deletion("payment_allocation",
                        """
                        DELETE FROM payment_allocation WHERE payment_id IN (SELECT id FROM payment WHERE registration_id IN
                        (SELECT id FROM registration WHERE event_id = :eventId) OR organization_id IN (SELECT id FROM
                        organization WHERE event_id = :eventId)) OR registration_id IN (SELECT id FROM registration WHERE
                        event_id = :eventId)
                        """),
                new Deletion("payment",
                        """
                        DELETE FROM payment WHERE registration_id IN (SELECT id FROM registration WHERE event_id = :eventId)
                        OR organization_id IN (SELECT id FROM organization WHERE event_id = :eventId)
                        """),
                new Deletion("reservation_item",
                        """
                        DELETE FROM reservation_item WHERE reservation_id IN (SELECT id FROM reservation WHERE
                        registration_id IN (SELECT id FROM registration WHERE event_id = :eventId)) OR capacity_id IN (SELECT
                        id FROM capacity WHERE event_id = :eventId)
                        """),
                new Deletion("reservation",
                        """
                        DELETE FROM reservation WHERE registration_id IN (SELECT id FROM registration WHERE event_id =
                        :eventId)
                        """),
                new Deletion("registration_terms_agreement",
                        """
                        DELETE FROM registration_terms_agreement WHERE registration_id IN (SELECT id FROM registration WHERE
                        event_id = :eventId)
                        """),
                new Deletion("cash_receipt",
                        """
                        DELETE FROM cash_receipt WHERE event_id = :eventId
                        """),
                new Deletion("registration",
                        """
                        DELETE FROM registration WHERE event_id = :eventId
                        """),
                new Deletion("organization",
                        """
                        DELETE FROM organization WHERE event_id = :eventId
                        """),
                new Deletion("capacity_category",
                        """
                        DELETE FROM capacity_category WHERE capacity_id IN (SELECT id FROM capacity WHERE event_id =
                        :eventId) OR event_category_id IN (SELECT id FROM event_category WHERE event_id = :eventId)
                        """),
                new Deletion("capacity",
                        """
                        DELETE FROM capacity WHERE event_id = :eventId
                        """),
                new Deletion("event_category_souvenir_policy",
                        """
                        DELETE FROM event_category_souvenir_policy WHERE event_category_souvenir_id IN (SELECT id FROM
                        event_category_souvenir WHERE event_category_id IN (SELECT id FROM event_category WHERE event_id =
                        :eventId) OR souvenir_id IN (SELECT id FROM souvenir WHERE event_id = :eventId))
                        """),
                new Deletion("event_category_souvenir",
                        """
                        DELETE FROM event_category_souvenir WHERE event_category_id IN (SELECT id FROM event_category WHERE
                        event_id = :eventId) OR souvenir_id IN (SELECT id FROM souvenir WHERE event_id = :eventId)
                        """),
                new Deletion("event_category_registration_policy",
                        """
                        DELETE FROM event_category_registration_policy WHERE event_category_id IN (SELECT id FROM
                        event_category WHERE event_id = :eventId)
                        """),
                new Deletion("event_category_start_group",
                        """
                        DELETE FROM event_category_start_group WHERE event_category_id IN (SELECT id FROM event_category
                        WHERE event_id = :eventId)
                        """),
                new Deletion("souvenir",
                        """
                        DELETE FROM souvenir WHERE event_id = :eventId
                        """),
                new Deletion("event_category",
                        """
                        DELETE FROM event_category WHERE event_id = :eventId
                        """),
                new Deletion("event_registration_policy",
                        """
                        DELETE FROM event_registration_policy WHERE event_id = :eventId
                        """),
                new Deletion("registration_action_policy",
                        """
                        DELETE FROM registration_action_policy WHERE event_id = :eventId
                        """),
                new Deletion("event",
                        """
                        DELETE FROM event WHERE id = :eventId
                        """)
        );
    }

    /** 고정된 정리 대상 테이블과 파라미터화한 삭제문이다. */
    private record Deletion(String table, String sql) { }
}
