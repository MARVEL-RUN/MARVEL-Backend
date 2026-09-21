package kr.co.teambrain.marvelrun.user.capacity.command.repository;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 DB에서 Capacity의 조건부 UPDATE가 한도를 준수하는지 검증한다.
 *
 * 기존 대회에 테스트용 Capacity만 추가하며,
 * 각 테스트의 데이터 변경은 종료 시 롤백한다.
 *
 * 서비스 상태 전이와 동시 신청은 별도 테스트에서 검증한다.
 */
@Tag("capacity-db")
@DataJpaTest
@ActiveProfiles("capacity-test")
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
class CapacityCommandRepositoryDatabaseTest {

    @Autowired
    private CapacityCommandRepository capacityRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${MARVELRUN_TEST_EVENT_ID}")
    private String eventId;

    private String capacityId;

    private static final LocalDateTime NOW =
            LocalDateTime.of(2026, 9, 20, 12, 0);

    /**
     * 기존 대회 존재 여부를 확인하고 테스트용 정원 행을 생성한다.
     *
     * 한도 3명 중 확정 1명, 임시 확보 1명으로 시작하므로
     * 추가 확보 가능한 수량은 1명이다.
     */
    @BeforeEach
    void prepareCapacity() {
        Integer eventCount = jdbcTemplate.queryForObject(
                "select count(*) from event where id = ?",
                Integer.class,
                eventId
        );

        assertThat(eventCount)
                .as("MARVELRUN_TEST_EVENT_ID에 해당하는 대회가 필요합니다.")
                .isEqualTo(1);

        capacityId = UUID.randomUUID().toString();

        /*
         * 이 테스트는 Repository의 수량 조건만 검증한다.
         * 실제 신청 대상 조회와 섞이지 않도록 테스트 전용 키를 사용한다.
         */
        jdbcTemplate.update(
                """
                insert into capacity (
                    id, event_id, type, resource_key, name,
                    souvenir_id, size,
                    limit_count, held_count, confirmed_count,
                    active, created_at, updated_at
                ) values (
                    ?, ?, 'CATEGORY_GROUP', ?, ?,
                    null, '',
                    3, 1, 1,
                    true, ?, ?
                )
                """,
                capacityId,
                eventId,
                "TEST:" + capacityId,
                "원자적 수량 검증",
                NOW,
                NOW
        );
    }

    /**
     * 확정 수량과 임시 확보 수량을 합산해 마지막 한 자리만 허용한다.
     *
     * 한도 도달 후 추가 확보는 실패하며 기존 수량을 변경하지 않는다.
     */
    @Test
    void acquireOnlyWithinRemainingCapacity() {
        assertThat(capacityRepository.acquireHeld(
                eventId, capacityId, 1, NOW
        )).isEqualTo(1);

        assertThat(capacityRepository.acquireHeld(
                eventId, capacityId, 1, NOW
        )).isZero();

        assertCounts(2, 1);
    }

    /**
     * 남은 한 자리보다 많은 수량을 요청하면 일부 확보 없이 실패한다.
     */
    @Test
    void rejectQuantityExceedingRemainingCapacity() {
        assertThat(capacityRepository.acquireHeld(
                eventId, capacityId, 2, NOW
        )).isZero();

        assertCounts(1, 1);
    }

    /**
     * 영속성 컨텍스트의 엔티티 값 대신 DB에 반영된 카운터를 확인한다.
     */
    private void assertCounts(int expectedHeld, int expectedConfirmed) {
        Integer held = jdbcTemplate.queryForObject(
                "select held_count from capacity where id = ?",
                Integer.class,
                capacityId
        );

        Integer confirmed = jdbcTemplate.queryForObject(
                "select confirmed_count from capacity where id = ?",
                Integer.class,
                capacityId
        );

        assertThat(held).isEqualTo(expectedHeld);
        assertThat(confirmed).isEqualTo(expectedConfirmed);
    }

    /**
     * 임시 확보를 확정하면 held가 감소하고 confirmed가 증가한다.
     *
     * 전환 전후의 전체 점유 수량은 동일하게 유지된다.
     */
    @Test
    void confirmHeldMovesQuantityWithoutChangingTotal() {
        assertThat(capacityRepository.confirmHeld(
                eventId, capacityId, 1, NOW
        )).isEqualTo(1);

        assertCounts(0, 2);
    }

    /**
     * 임시 확보를 반환하면 held만 감소하고 confirmed는 유지된다.
     */
    @Test
    void releaseHeldDecreasesOnlyHeldCount() {
        assertThat(capacityRepository.releaseHeld(
                eventId, capacityId, 1, NOW
        )).isEqualTo(1);

        assertCounts(0, 1);
    }

    /**
     * 확보 수량보다 큰 확정·반환 요청은 실패하고 기존 수량을 유지한다.
     *
     * 남은 확보를 모두 반환한 이후에도 추가 확정·반환을 차단하여
     * held가 음수가 되지 않는지 검증한다.
     */
    @Test
    void rejectConfirmAndReleaseWhenHeldIsInsufficient() {
        /*
         * held=1인 상태에서 2명을 처리하려는 요청은
         * 일부 처리 없이 실패해야 한다.
         */
        assertThat(capacityRepository.confirmHeld(
                eventId, capacityId, 2, NOW
        )).isZero();

        assertCounts(1, 1);

        assertThat(capacityRepository.releaseHeld(
                eventId, capacityId, 2, NOW
        )).isZero();

        assertCounts(1, 1);

        // 남은 1명을 정상 반환한다.
        assertThat(capacityRepository.releaseHeld(
                eventId, capacityId, 1, NOW
        )).isEqualTo(1);

        assertCounts(0, 1);

        // 확보가 없는 상태에서는 확정과 추가 반환이 모두 실패한다.
        assertThat(capacityRepository.confirmHeld(
                eventId, capacityId, 1, NOW
        )).isZero();

        assertThat(capacityRepository.releaseHeld(
                eventId, capacityId, 1, NOW
        )).isZero();

        assertCounts(0, 1);
    }

    /**
     * 비활성 Capacity는 잔여 수량이 있어도 신규 확보를 차단한다.
     *
     * 비활성화 이전의 확보분은 정상적으로 확정할 수 있다.
     */
    @Test
    void inactiveCapacityRejectsAcquireButAllowsConfirm() {
        jdbcTemplate.update(
                "update capacity set active = false where id = ?",
                capacityId
        );

        // 잔여 1명이 있지만 active=false이므로 신규 확보는 실패한다.
        assertThat(capacityRepository.acquireHeld(
                eventId, capacityId, 1, NOW
        )).isZero();

        assertCounts(1, 1);

        // 기존 확보분의 확정은 허용한다.
        assertThat(capacityRepository.confirmHeld(
                eventId, capacityId, 1, NOW
        )).isEqualTo(1);

        assertCounts(0, 2);
    }

    /**
     * 비활성 Capacity도 기존 임시 확보의 반환은 허용한다.
     */
    @Test
    void inactiveCapacityAllowsRelease() {
        jdbcTemplate.update(
                "update capacity set active = false where id = ?",
                capacityId
        );

        assertThat(capacityRepository.releaseHeld(
                eventId, capacityId, 1, NOW
        )).isEqualTo(1);

        assertCounts(0, 1);
    }
}