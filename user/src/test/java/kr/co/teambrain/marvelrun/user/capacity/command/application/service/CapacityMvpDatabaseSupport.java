package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import jakarta.persistence.EntityManager;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.*;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.*;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.*;
import kr.co.teambrain.marvelrun.user.event.command.application.service.*;
import kr.co.teambrain.marvelrun.user.event.command.application.util.PaymentOrderIdGenerator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.*;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.loader.RegistrationPolicyLoader;
import kr.co.teambrain.marvelrun.user.payment.command.application.*;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.*;
import kr.co.teambrain.marvelrun.user.payment.command.application.valid.EventPaymentPolicyValidator;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.TossConfirmFailureClassifier;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.client.TossPaymentClient;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.dto.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * 정원·예약 MVP 서비스 테스트의 공통 DB 환경을 구성한다.
 *
 * 실제 신청 Validator와 서비스, 결제 트랜잭션 서비스를 사용한다.
 * 외부 Toss와 서버 시각만 Mock으로 대체한다.
 *
 * 테스트 전용 대회에 생성한 데이터만 정리하며 기존 정책 데이터는 보존한다.
 */
@Tag("capacity-db")
@DataJpaTest(
        showSql = false,
        properties = {
                "spring.datasource.hikari.maximum-pool-size=4",
                "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=10"
        }
)
@ActiveProfiles("capacity-test")
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        CapacityHoldService.class,
        RegistrationCapacityService.class,
        ReservationReleaseService.class,
        ReservationPaymentService.class,
        RegistrationCommandService.class,
        OrgRegistrationCommandService.class,
        RegistrationApplyValidator.class,
        OrgRegistrationApplyValidator.class,
        RegistrationPolicyValidator.class,
        RegistrationPolicyLoader.class,
        EventPaymentPolicyValidator.class,
        PaymentCreator.class,
        PaymentOrderIdGenerator.class,
        PaymentConfirmTransactionService.class,
        PaymentConfirmService.class,
        TossConfirmFailureClassifier.class
})
abstract class CapacityMvpDatabaseSupport {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected EntityManager entityManager;

    @Autowired
    protected PlatformTransactionManager transactionManager;

    @Autowired
    protected RegistrationCommandService registrations;

    @Autowired
    protected OrgRegistrationCommandService organizations;

    @Autowired
    protected CapacityHoldService holdService;

    @Autowired
    protected PaymentConfirmService confirmService;

    @Autowired
    protected PaymentConfirmTransactionService paymentTx;

    @MockitoBean
    protected ServerTimeProvider time;

    @MockitoBean
    protected TossPaymentClient toss;

    /*
     * 기본적으로 실제 메서드를 실행한다.
     * 신청 생성 롤백 테스트에서만 저장 후 실패를 주입한다.
     */
    @MockitoSpyBean
    protected PaymentCreator paymentCreator;

    protected TransactionTemplate tx;

    protected String eventId;
    protected String categoryA;
    protected String categoryB;
    protected String souvenirId;

    protected String total;
    protected String categoryCapacityA;
    protected String categoryCapacityB;

    protected final Map<String, String> shirts = new HashMap<>();

    protected static final LocalDateTime NOW =
            LocalDateTime.of(2026, 9, 20, 12, 0);

    protected static final LocalDateTime EVENT_DATE =
            LocalDateTime.of(2026, 10, 20, 9, 0);

    /**
     * 매 테스트마다 독립적인 대회·종목·기념품·정책·Capacity를 생성한다.
     *
     * 기본 한도는 각각 20명이며, 테스트 메서드에서 필요한 한도만 조정한다.
     * 두 종목이 하나의 기념품과 사이즈별 재고를 공유한다.
     */
    @BeforeEach
    void prepareMvpData() {
        tx = new TransactionTemplate(transactionManager);
        when(time.currentDateTime()).thenReturn(NOW);

        eventId = UUID.randomUUID().toString();
        categoryA = UUID.randomUUID().toString();
        categoryB = UUID.randomUUID().toString();
        souvenirId = UUID.randomUUID().toString();
        shirts.clear();

        tx.executeWithoutResult(status -> {
            jdbc.update(
                    """
                    insert into event (
                        id, name_kr, start_date, region, host, organizer,
                        event_status, visible_status,
                        regist_start_date, regist_deadline, payment_deadline,
                        auto_max_regist, auto_start, auto_deadline,
                        phone_auth_required
                    ) values (
                        ?, ?, ?, ?, ?, ?,
                        'OPEN', 'OPEN',
                        ?, ?, ?,
                        true, true, true, false
                    )
                    """,
                    eventId, "MVP 통합 테스트", EVENT_DATE,
                    "테스트", "테스트", "테스트",
                    NOW.minusDays(1), NOW.plusDays(1), NOW.plusDays(2)
            );

            jdbc.update(
                    """
                    insert into event_registration_policy (
                        id, event_id, guardian_required_birth_from
                    ) values (?, ?, ?)
                    """,
                    UUID.randomUUID().toString(),
                    eventId,
                    LocalDate.of(2012, 10, 21)
            );

            jdbc.update(
                    """
                    insert into souvenir (
                        id, event_id, name, sizes, is_active, sort_order
                    ) values (?, ?, ?, ?, true, 0)
                    """,
                    souvenirId, eventId, "테스트 티셔츠", "S|M|150"
            );

            for (String categoryId : List.of(categoryA, categoryB)) {
                jdbc.update(
                        """
                        insert into event_category (
                            id, event_id, amount, name, is_active, sort_order
                        ) values (?, ?, 40000, ?, true, 0)
                        """,
                        categoryId, eventId, "테스트 종목"
                );

                // 출생일 제한 없는 종목도 정책 행 자체는 필요하다.
                jdbc.update(
                        """
                        insert into event_category_registration_policy (
                            id, event_category_id,
                            allowed_birth_from, allowed_birth_to
                        ) values (?, ?, null, null)
                        """,
                        UUID.randomUUID().toString(), categoryId
                );

                jdbc.update(
                        """
                        insert into event_category_souvenir (
                            id, event_category_id, souvenir_id, created_at
                        ) values (?, ?, ?, ?)
                        """,
                        UUID.randomUUID().toString(),
                        categoryId, souvenirId, NOW
                );
            }

            total = addCapacity("EVENT_TOTAL", 20, null, "");
            categoryCapacityA = addCapacity("CATEGORY", 20, null, "");
            categoryCapacityB = addCapacity("CATEGORY", 20, null, "");

            linkCategory(categoryCapacityA, categoryA);
            linkCategory(categoryCapacityB, categoryB);

            for (String size : List.of("S", "M", "150")) {
                shirts.put(
                        size,
                        addCapacity("SOUVENIR", 20, souvenirId, size)
                );
            }
        });
    }

    /**
     * 테스트용 Capacity를 생성하고 ID를 반환한다.
     *
     * 전체 정원이 다른 정원보다 먼저 처리되도록 ID 접두사를 구분한다.
     */
    protected String addCapacity(
            String type,
            int limit,
            String targetSouvenir,
            String size
    ) {
        String prefix = switch (type) {
            case "EVENT_TOTAL" -> "0-";
            case "CATEGORY" -> "1-";
            default -> "2-";
        };

        String id = prefix + UUID.randomUUID();

        jdbc.update(
                """
                insert into capacity (
                    id, event_id, type, resource_key, name,
                    souvenir_id, size,
                    limit_count, held_count, confirmed_count,
                    active, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, true, ?, ?)
                """,
                id, eventId, type, "TEST:" + id, "테스트 자원",
                targetSouvenir, size, limit, NOW, NOW
        );

        return id;
    }

    /**
     * 정원 Capacity와 적용 종목을 연결한다.
     */
    protected void linkCategory(String capacityId, String categoryId) {
        jdbc.update(
                """
                insert into capacity_category (
                    id, capacity_id, event_category_id
                ) values (?, ?, ?)
                """,
                UUID.randomUUID().toString(), capacityId, categoryId
        );
    }

    /**
     * 테스트 전용 Capacity의 한도를 변경한다.
     */
    protected void setLimit(String capacityId, int limit) {
        jdbc.update(
                "update capacity set limit_count = ? where id = ?",
                limit, capacityId
        );
    }

    /**
     * 실제 개인 신청 서비스를 호출한다.
     *
     * 테스트 간 중복 참가자 충돌을 피하도록 매번 다른 이름을 사용한다.
     */
    protected RegistrationCreateResponse personal(
            String categoryId,
            String size,
            String birth
    ) {
        return registrations.register(
                eventId,
                new RegistrationCreateRequest(
                        categoryId,
                        List.of(new SouvenirJson(souvenirId, size)),
                        "Test123!",
                        "참가" + UUID.randomUUID().toString().substring(0, 8),
                        "010-0000-0000",
                        birth,
                        GenderClass.M,
                        "테스트 주소",
                        "상세",
                        "테스트 보호자",
                        true
                )
        );
    }

    /**
     * 실제 단체 신청 서비스를 호출한다.
     *
     * 매개변수로 전달한 종목마다 성인 참가자 한 명을 생성한다.
     * 모든 참가자는 공유 티셔츠의 S 사이즈를 선택한다.
     */
    protected OrgRegistrationCreateResponse group(String... categoryIds) {
        List<OrgRegistrationParticipantRequest> members = new ArrayList<>();

        for (int i = 0; i < categoryIds.length; i++) {
            members.add(new OrgRegistrationParticipantRequest(
                    categoryIds[i],
                    List.of(new SouvenirJson(souvenirId, "S")),
                    "단체참가자" + i,
                    "010-0000-0000",
                    "1990-01-01",
                    GenderClass.M
            ));
        }

        return organizations.register(
                eventId,
                new OrgRegistrationCreateRequest(
                        new OrgAccountRequest(
                                "테스트 단체",
                                "g" + UUID.randomUUID().toString().substring(0, 12),
                                "Test123!"
                        ),
                        new OrgProfileRequest(
                                "테스트 주소",
                                "상세",
                                LocalDate.of(1990, 1, 1),
                                "010-0000-0000",
                                "test@example.com",
                                "테스트 단체장",
                                true
                        ),
                        members
                )
        );
    }

    /**
     * 정수형 단일 조회 결과를 반환한다.
     */
    protected int n(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    /**
     * 문자열 단일 조회 결과를 반환한다.
     *
     * nullable 컬럼은 null을 반환할 수 있다.
     */
    protected String s(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    /**
     * DB의 임시·확정 수량을 함께 검증한다.
     */
    protected void counters(String capacityId, int held, int confirmed) {
        assertThat(n(
                "select held_count from capacity where id = ?", capacityId
        )).isEqualTo(held);

        assertThat(n(
                "select confirmed_count from capacity where id = ?", capacityId
        )).isEqualTo(confirmed);
    }

    /**
     * 신청에 연결된 예약 식별자를 조회한다.
     */
    protected String reservationId(String registrationId) {
        return s(
                "select id from reservation where registration_id = ?",
                registrationId
        );
    }

    /**
     * 예약 상태·확보 회차·JSON 이력 개수·무기한 확보 설정을 검증한다.
     */
    protected void reservation(
            String registrationId,
            String expectedStatus,
            int sequence,
            int historySize
    ) {
        String id = reservationId(registrationId);

        assertThat(s(
                "select status from reservation where id = ?", id
        )).isEqualTo(expectedStatus);

        assertThat(n(
                "select hold_sequence from reservation where id = ?", id
        )).isEqualTo(sequence);

        assertThat(n(
                "select json_length(history) from reservation where id = ?", id
        )).isEqualTo(historySize);

        assertThat(n(
                "select count(*) from reservation where id = ? and expires_at is null",
                id
        )).isEqualTo(1);
    }

    /**
     * 현재 예약 상세 행의 ID 목록을 조회한다.
     */
    protected List<String> itemIds(String registrationId) {
        return jdbc.queryForList(
                "select id from reservation_item where reservation_id = ?",
                String.class,
                reservationId(registrationId)
        );
    }

    /**
     * 발생한 도메인 예외의 ErrorCode까지 검증한다.
     */
    protected void error(ErrorCode expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        CustomException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(expected)
                );
    }

    /**
     * 테스트 신청에서 생성한 Payment의 승인 요청을 구성한다.
     */
    protected PaymentConfirmRequest confirmRequest(String paymentId) {
        return new PaymentConfirmRequest(
                "test-key-" + UUID.randomUUID(),
                s("select order_id from payment where id = ?", paymentId),
                jdbc.queryForObject(
                        "select amount from payment where id = ?",
                        BigDecimal.class,
                        paymentId
                ).longValueExact()
        );
    }

    /**
     * 저장된 Payment로부터 완료 재실행 검증용 Context를 구성한다.
     */
    protected PaymentConfirmContext savedContext(String paymentId) {
        return new PaymentConfirmContext(
                paymentId,
                s("select registration_id from payment where id = ?", paymentId),
                s("select organization_id from payment where id = ?", paymentId),
                s("select payment_key from payment where id = ?", paymentId),
                s("select order_id from payment where id = ?", paymentId),
                jdbc.queryForObject(
                        "select amount from payment where id = ?",
                        BigDecimal.class,
                        paymentId
                ).longValueExact(),
                s(
                        "select confirm_idempotency_key from payment where id = ?",
                        paymentId
                ),
                UUID.randomUUID().toString()
        );
    }

    /**
     * 실제 통신 없이 Toss 성공 응답을 구성한다.
     */
    protected TossPaymentConfirmResponse success(
            String paymentKey,
            String orderId,
            long amount
    ) {
        return new TossPaymentConfirmResponse(
                paymentKey,
                orderId,
                "테스트 결제",
                "DONE",
                "카드",
                amount,
                NOW.atOffset(ZoneOffset.ofHours(9)),
                NOW.plusSeconds(1).atOffset(ZoneOffset.ofHours(9)),
                UUID.randomUUID().toString(),
                null,
                new TossPaymentConfirmResponse.Receipt(
                        "https://example.invalid/test-receipt"
                )
        );
    }

    /**
     * Toss Mock이 성공을 반환하도록 구성한다.
     *
     * 외부 호출 시점에 DB 트랜잭션이 없고,
     * 승인 시작 상태가 이미 커밋되어 있는지도 검증한다.
     */
    protected void mockSuccess() {
        when(toss.confirm(any(TossPaymentConfirmRequest.class), anyString()))
                .thenAnswer(invocation -> {
                    TossPaymentConfirmRequest request =
                            invocation.getArgument(0);

                    assertThat(
                            TransactionSynchronizationManager
                                    .isActualTransactionActive()
                    ).isFalse();

                    assertThat(s(
                            "select process_status from payment where order_id = ?",
                            request.orderId()
                    )).isEqualTo("CONFIRMING");

                    return success(
                            request.paymentKey(),
                            request.orderId(),
                            request.amount()
                    );
                });
    }

    /**
     * 테스트 대회에 연결된 Payment 개수를 조회한다.
     */
    protected int paymentCount() {
        return n(
                """
                select count(*) from payment
                where registration_id in (
                    select id from registration where event_id = ?
                ) or organization_id in (
                    select id from organization where event_id = ?
                )
                """,
                eventId, eventId
        );
    }

    /**
     * 테스트 전용 대회의 데이터만 외래 키 참조 역순으로 삭제한다.
     *
     * 애플리케이션 서비스를 호출하지 않으므로
     * 테스트에서 주입한 Mock 실패와 무관하게 정리한다.
     */
    @AfterEach
    void cleanupMvpData() {
        tx.executeWithoutResult(status -> {
            jdbc.update(
                    """
                    delete from payment_process_log
                    where payment_id in (
                        select id from payment
                        where registration_id in (
                            select id from registration where event_id = ?
                        ) or organization_id in (
                            select id from organization where event_id = ?
                        )
                    )
                    """,
                    eventId, eventId
            );

            jdbc.update(
                    """
                    delete from payment
                    where registration_id in (
                        select id from registration where event_id = ?
                    ) or organization_id in (
                        select id from organization where event_id = ?
                    )
                    """,
                    eventId, eventId
            );

            jdbc.update(
                    """
                    delete from reservation_item
                    where capacity_id in (
                        select id from capacity where event_id = ?
                    )
                    """,
                    eventId
            );

            jdbc.update(
                    """
                    delete from reservation
                    where registration_id in (
                        select id from registration where event_id = ?
                    )
                    """,
                    eventId
            );

            jdbc.update("delete from registration where event_id = ?", eventId);
            jdbc.update("delete from organization where event_id = ?", eventId);

            jdbc.update(
                    """
                    delete from capacity_category
                    where capacity_id in (
                        select id from capacity where event_id = ?
                    )
                    """,
                    eventId
            );

            jdbc.update("delete from capacity where event_id = ?", eventId);

            jdbc.update(
                    """
                    delete from event_category_souvenir
                    where event_category_id in (
                        select id from event_category where event_id = ?
                    )
                    """,
                    eventId
            );

            jdbc.update(
                    """
                    delete from event_category_registration_policy
                    where event_category_id in (
                        select id from event_category where event_id = ?
                    )
                    """,
                    eventId
            );

            jdbc.update("delete from souvenir where event_id = ?", eventId);
            jdbc.update("delete from event_category where event_id = ?", eventId);
            jdbc.update("delete from event_registration_policy where event_id = ?", eventId);
            jdbc.update("delete from event where id = ?", eventId);
        });
    }
}