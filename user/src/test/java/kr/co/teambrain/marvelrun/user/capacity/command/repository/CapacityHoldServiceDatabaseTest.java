package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import jakarta.persistence.EntityManager;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityHoldRequest;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 확보 서비스를 호출하여 신청·예약·상세·카운터의 저장과 롤백을 검증한다.
 *
 * 테스트 전용 대회와 종목을 사용하므로 기존 정책 데이터는 수정하지 않는다.
 * 테스트 전체의 자동 트랜잭션 대신 명시적인 트랜잭션을 사용하여
 * 커밋 또는 롤백 이후의 DB 상태를 검증한다.
 */
@Tag("capacity-db")
@DataJpaTest
@ActiveProfiles("capacity-test")
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@Import({
        CapacityHoldService.class,
        CapacityRequirementResolver.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CapacityHoldServiceDatabaseTest {

    @Autowired
    private CapacityHoldService capacityHoldService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    private String eventId;
    private String categoryId;
    private String totalCapacityId;
    private String categoryCapacityId;

    private static final LocalDateTime NOW =
            LocalDateTime.of(2026, 9, 20, 12, 0);

    /**
     * 테스트 전용 대회·종목과 잔여 1명인 정원 두 개를 생성한다.
     *
     * ID 순으로 전체 정원이 먼저, 종목 정원이 나중에 처리되도록 구성한다.
     * 초기 데이터는 실제 확보 트랜잭션과 분리하여 커밋한다.
     */
    @BeforeEach
    void prepareFixtures() {
        transaction = new TransactionTemplate(transactionManager);

        eventId = UUID.randomUUID().toString();
        categoryId = UUID.randomUUID().toString();

        totalCapacityId = "0-" + UUID.randomUUID();
        categoryCapacityId = "1-" + UUID.randomUUID();

        transaction.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    """
                    insert into event (
                        id, name_kr, start_date,
                        region, host, organizer,
                        event_status, visible_status,
                        regist_start_date, regist_deadline, payment_deadline,
                        auto_max_regist, auto_start, auto_deadline,
                        phone_auth_required
                    ) values (
                        ?, ?, ?,
                        ?, ?, ?,
                        'OPEN', 'OPEN',
                        ?, ?, ?,
                        true, true, true,
                        false
                    )
                    """,
                    eventId,
                    "확보 서비스 테스트",
                    NOW.plusMonths(1),
                    "테스트",
                    "테스트",
                    "테스트",
                    NOW.minusDays(1),
                    NOW.plusDays(1),
                    NOW.plusDays(2)
            );

            jdbcTemplate.update(
                    """
                    insert into event_category (
                        id, event_id, amount, name, is_active, sort_order
                    ) values (?, ?, 40000, ?, true, 0)
                    """,
                    categoryId,
                    eventId,
                    "테스트 종목"
            );

            insertCapacity(totalCapacityId, "EVENT_TOTAL");
            insertCapacity(categoryCapacityId, "CATEGORY");

            jdbcTemplate.update(
                    """
                    insert into capacity_category (
                        id, capacity_id, event_category_id
                    ) values (?, ?, ?)
                    """,
                    UUID.randomUUID().toString(),
                    categoryCapacityId,
                    categoryId
            );
        });
    }

    /**
     * 신청 저장과 실제 확보 서비스를 같은 트랜잭션에서 실행한다.
     *
     * 커밋 후 예약 한 건, 자원별 상세 두 건,
     * 각 Capacity의 held 증가와 최초 HOLD 이력을 확인한다.
     */
    @Test
    void holdAllPersistsReservationItemsAndCounters() {
        String registrationId = transaction.execute(status -> {
            Registration registration = persistRegistration();

            capacityHoldService.holdAll(
                    eventId,
                    List.of(new CapacityHoldRequest(registration, false)),
                    NOW
            );

            return registration.getId();
        });

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from registration where id = ?",
                Integer.class,
                registrationId
        )).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from reservation where registration_id = ?",
                Integer.class,
                registrationId
        )).isEqualTo(1);

        String reservationId = jdbcTemplate.queryForObject(
                "select id from reservation where registration_id = ?",
                String.class,
                registrationId
        );

        assertThat(jdbcTemplate.queryForObject(
                "select status from reservation where id = ?",
                String.class,
                reservationId
        )).isEqualTo("HELD");

        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*) from reservation
                where id = ? and expires_at is null
                """,
                Integer.class,
                reservationId
        )).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                "select hold_sequence from reservation where id = ?",
                Integer.class,
                reservationId
        )).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                "select json_length(history) from reservation where id = ?",
                Integer.class,
                reservationId
        )).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                """
                select json_unquote(json_extract(history, '$[0].action'))
                from reservation where id = ?
                """,
                String.class,
                reservationId
        )).isEqualTo("HOLD");

        assertThat(jdbcTemplate.queryForList(
                """
                select capacity_id from reservation_item
                where reservation_id = ?
                """,
                String.class,
                reservationId
        )).containsExactlyInAnyOrder(
                totalCapacityId,
                categoryCapacityId
        );

        assertThat(jdbcTemplate.queryForList(
                """
                select quantity from reservation_item
                where reservation_id = ?
                """,
                Integer.class,
                reservationId
        )).containsExactlyInAnyOrder(1, 1);

        assertHeld(totalCapacityId, 1);
        assertHeld(categoryCapacityId, 1);
    }

    /**
     * 전체 정원 확보 이후 종목 정원 확보가 실패하면
     * 전체 정원 증가와 같은 트랜잭션의 신청 저장까지 롤백되는지 검증한다.
     *
     * 실패는 테스트 코드가 아니라 실제 holdAll에서 발생해야 한다.
     */
    @Test
    void holdAllFailureRollsBackRegistrationAndEarlierAcquire() {
        /*
         * 종목 정원을 이미 확정 참가자 한 명이 점유한 상태로 만든다.
         * 전체 정원은 잔여 1명인 상태를 유지한다.
         */
        transaction.executeWithoutResult(status ->
                jdbcTemplate.update(
                        """
                        update capacity
                        set confirmed_count = 1
                        where id = ?
                        """,
                        categoryCapacityId
                )
        );

        AtomicReference<String> registrationId = new AtomicReference<>();

        assertThatThrownBy(() ->
                transaction.executeWithoutResult(status -> {
                    Registration registration = persistRegistration();
                    registrationId.set(registration.getId());

                    capacityHoldService.holdAll(
                            eventId,
                            List.of(
                                    new CapacityHoldRequest(registration, false)
                            ),
                            NOW
                    );
                })
        ).isInstanceOfSatisfying(
                CustomException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.CAPACITY_ACQUIRE_FAILED)
        );

        /*
         * 확보 트랜잭션이 종료된 뒤 DB를 조회한다.
         * 전체 정원의 앞선 증가분과 신규 신청은 남아 있으면 안 된다.
         */
        assertHeld(totalCapacityId, 0);
        assertHeld(categoryCapacityId, 0);

        assertThat(jdbcTemplate.queryForObject(
                "select confirmed_count from capacity where id = ?",
                Integer.class,
                categoryCapacityId
        )).isEqualTo(1);

        assertThat(registrationId.get()).isNotNull();

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from registration where id = ?",
                Integer.class,
                registrationId.get()
        )).isZero();

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from reservation where registration_id = ?",
                Integer.class,
                registrationId.get()
        )).isZero();

        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*) from reservation_item
                where capacity_id in (?, ?)
                """,
                Integer.class,
                totalCapacityId,
                categoryCapacityId
        )).isZero();
    }

    /**
     * 한도 1명, 점유 0명인 테스트 Capacity를 생성한다.
     *
     * EVENT_TOTAL은 대회에 직접 적용되며,
     * CATEGORY는 별도의 capacity_category 행으로 종목에 연결한다.
     */
    private void insertCapacity(String capacityId, String type) {
        jdbcTemplate.update(
                """
                insert into capacity (
                    id, event_id, type, resource_key, name,
                    souvenir_id, size,
                    limit_count, held_count, confirmed_count,
                    active, created_at, updated_at
                ) values (
                    ?, ?, ?, ?, ?,
                    null, '',
                    1, 0, 0,
                    true, ?, ?
                )
                """,
                capacityId,
                eventId,
                type,
                type,
                "테스트 정원",
                NOW,
                NOW
        );
    }

    /**
     * 확보 서비스의 입력으로 사용할 미결제 신청을 저장한다.
     *
     * 이 테스트는 정책 검증 이후의 확보 계층을 대상으로 하므로
     * 신청 Validator와 Payment 생성은 호출하지 않는다.
     * 기념품 없이 전체·종목 정원 두 자원만 사용한다.
     */
    private Registration persistRegistration() {
        Registration registration = Registration.builder()
                .event(entityManager.getReference(Event.class, eventId))
                .eventCategory(
                        entityManager.getReference(EventCategory.class, categoryId)
                )
                .name("확보테스트")
                .phNum("01000000000")
                .birth("1990-01-01")
                .gender(GenderClass.M)
                .password("test-only")
                .souvenirJson(List.of())
                .contractAmount(BigDecimal.valueOf(40000))
                .paidAmount(BigDecimal.ZERO)
                .status(RegistrationStatus.PAYMENT_PENDING)
                .build();

        entityManager.persist(registration);
        entityManager.flush();

        return registration;
    }

    /**
     * 커밋 또는 롤백 이후 DB에 남은 임시 확보 수량을 검증한다.
     */
    private void assertHeld(String capacityId, int expected) {
        assertThat(jdbcTemplate.queryForObject(
                "select held_count from capacity where id = ?",
                Integer.class,
                capacityId
        )).isEqualTo(expected);
    }

    /**
     * 성공·실패 여부와 관계없이 이 테스트 대회의 데이터만 정리한다.
     *
     * 외래 키 참조 순서에 따라 상세부터 삭제하며,
     * 기존 대회와 정책 데이터에는 접근하지 않는다.
     */
    @AfterEach
    void cleanupFixtures() {
        transaction.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    """
                    delete from reservation_item
                    where capacity_id in (?, ?)
                    """,
                    totalCapacityId,
                    categoryCapacityId
            );

            jdbcTemplate.update(
                    """
                    delete from reservation
                    where registration_id in (
                        select id from registration where event_id = ?
                    )
                    """,
                    eventId
            );

            jdbcTemplate.update(
                    "delete from registration where event_id = ?",
                    eventId
            );

            jdbcTemplate.update(
                    """
                    delete from capacity_category
                    where capacity_id in (?, ?)
                    """,
                    totalCapacityId,
                    categoryCapacityId
            );

            jdbcTemplate.update(
                    "delete from capacity where event_id = ?",
                    eventId
            );

            jdbcTemplate.update(
                    "delete from event_category where event_id = ?",
                    eventId
            );

            jdbcTemplate.update(
                    "delete from event where id = ?",
                    eventId
            );
        });
    }
}