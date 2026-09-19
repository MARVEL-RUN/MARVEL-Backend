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
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentAllocationCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.generator.PaymentOrderIdGenerator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.*;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.loader.RegistrationPolicyLoader;
import kr.co.teambrain.marvelrun.user.payment.command.application.*;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.*;
import kr.co.teambrain.marvelrun.user.payment.command.application.valid.EventPaymentPolicyValidator;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.TossConfirmFailureClassifier;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.client.TossPaymentClient;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.dto.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 정원·예약 MVP 통합 테스트의 공통 데이터와 실행 환경을 제공한다.
 *
 * 정책 검증 및 업무 서비스는 실제 Bean을 사용한다.
 * 서버 시각과 Toss 클라이언트만 Mock으로 교체한다.
 * PaymentCreator의 Spy는 저장 이후 실패를 주입하는 테스트에서만 사용한다.
 *
 * 기존 정책 데이터와 분리된 대회·종목·기념품을 테스트마다 생성한다.
 */
@Tag("capacity-db")
@DataJpaTest(
        showSql = false,
        properties = {
                "spring.datasource.hikari.maximum-pool-size=4",
                "spring.datasource.hikari.connection-init-sql="
                        + "SET SESSION innodb_lock_wait_timeout=10"
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
        RegistrationPricingService.class,

        RegistrationApplyValidator.class,
        OrgRegistrationApplyValidator.class,
        RegistrationPolicyValidator.class,
        RegistrationPolicyLoader.class,
        EventPaymentPolicyValidator.class,

        PaymentCreator.class,
        PaymentAllocationCreator.class,
        PaymentOrderIdGenerator.class,

        PaymentConfirmService.class,
        PaymentConfirmTransactionService.class,
        TossConfirmFailureClassifier.class
})
abstract class CapacityMvpTestSupport {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected EntityManager em;

    @Autowired
    protected PlatformTransactionManager transactionManager;

    @Autowired
    protected RegistrationCommandService registrations;

    @Autowired
    protected OrgRegistrationCommandService organizations;

    @Autowired
    protected CapacityHoldService holdService;

    @Autowired
    protected PaymentConfirmService payments;

    @Autowired
    protected PaymentConfirmTransactionService paymentTransactions;

    @MockitoBean
    protected ServerTimeProvider time;

    @MockitoBean
    protected TossPaymentClient toss;

    @MockitoSpyBean
    protected PaymentCreator paymentCreator;

    /*
     * Allocation 생성 실패 시 신청 전체 Transaction rollback을
     * 검증하기 위해 실제 Bean을 Spy로 유지한다.
     */
    @MockitoSpyBean
    protected PaymentAllocationCreator paymentAllocationCreator;

    protected TransactionTemplate tx;

    protected String eventId;
    protected String categoryA;
    protected String categoryB;
    protected String souvenirId;

    protected String total;
    protected String categoryACapacity;
    protected String categoryBCapacity;
    protected String shirtS;
    protected String shirtM;
    protected String shirt150;

    protected static final LocalDateTime NOW =
            LocalDateTime.of(2026, 9, 20, 12, 0);

    protected static final LocalDateTime EVENT_DATE =
            LocalDateTime.of(2026, 10, 20, 9, 0);

    /**
     * 고정 시각과 테스트 전용 대회·정책·종목·사이즈 재고를 준비한다.
     *
     * 모든 기본 한도는 20명이며, 개별 테스트에서 필요한 한도만 조정한다.
     * 시나리오 시작 전 데이터는 커밋하여 별도 서비스 Tx에서 조회할 수 있게 한다.
     */
    @BeforeEach
    void prepareMvpFixtures() {
        tx = new TransactionTemplate(transactionManager);

        eventId = UUID.randomUUID().toString();
        categoryA = UUID.randomUUID().toString();
        categoryB = UUID.randomUUID().toString();
        souvenirId = UUID.randomUUID().toString();

        when(time.currentDateTime()).thenReturn(NOW);

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

            insertCategory(categoryA, "종목 A");
            insertCategory(categoryB, "종목 B");

            jdbc.update(
                    """
                    insert into souvenir (
                        id, event_id, name, sizes, is_active, sort_order
                    ) values (?, ?, ?, 'S|M|150', true, 0)
                    """,
                    souvenirId, eventId, "테스트 티셔츠"
            );

            for (String category : List.of(categoryA, categoryB)) {
                jdbc.update(
                        """
                        insert into event_category_souvenir (
                            id, event_category_id, souvenir_id, created_at
                        ) values (?, ?, ?, ?)
                        """,
                        UUID.randomUUID().toString(),
                        category, souvenirId, NOW
                );
            }

            /*
             * 앞자리로 UPDATE 순서를 고정한다.
             * 전체 → 종목 → 추가 제한 → 기념품 순서다.
             */
            total = addCapacity("0", "EVENT_TOTAL", null, "", 20);
            categoryACapacity = addCapacity("1", "CATEGORY", null, "", 20);
            categoryBCapacity = addCapacity("1", "CATEGORY", null, "", 20);

            linkCategory(categoryACapacity, categoryA);
            linkCategory(categoryBCapacity, categoryB);

            shirtS = addCapacity("3", "SOUVENIR", souvenirId, "S", 20);
            shirtM = addCapacity("3", "SOUVENIR", souvenirId, "M", 20);
            shirt150 = addCapacity("3", "SOUVENIR", souvenirId, "150", 20);
        });
    }

    /**
     * 출생일 제한 없는 테스트 종목과 필수 정책 행을 생성한다.
     */
    protected void insertCategory(String id, String name) {
        jdbc.update(
                """
                insert into event_category (
                    id, event_id, amount, name, is_active, sort_order
                ) values (?, ?, 40000, ?, true, 0)
                """,
                id, eventId, name
        );

        jdbc.update(
                """
                insert into event_category_registration_policy (
                    id, event_category_id, allowed_birth_from, allowed_birth_to
                ) values (?, ?, null, null)
                """,
                UUID.randomUUID().toString(), id
        );
    }

    /**
     * 테스트 자원을 생성하고 식별자를 반환한다.
     *
     * 반환된 ID 앞자리로 원자적 UPDATE 순서를 제어한다.
     */
    protected String addCapacity(
            String prefix,
            String type,
            String giftId,
            String size,
            int limit
    ) {
        String id = prefix + "-" + UUID.randomUUID();

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
                giftId, size, limit, NOW, NOW
        );

        return id;
    }

    /**
     * 종목에 적용할 정원 자원을 연결한다.
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
     * 개별 시나리오에서 사용할 최대 수량을 지정한다.
     */
    protected void limit(String capacityId, int value) {
        jdbc.update(
                "update capacity set limit_count = ? where id = ?",
                value, capacityId
        );
    }

    /**
     * 정책 검증과 실제 개인 신청 서비스를 거쳐 신청·예약·Payment를 생성한다.
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
                        "Test1234!",
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
     * 지정한 종목들에 성인 한 명씩 신청하는 실제 단체 요청을 실행한다.
     */
    protected OrgRegistrationCreateResponse group(String... categoryIds) {
        List<OrgRegistrationParticipantRequest> members =
                java.util.Arrays.stream(categoryIds)
                        .map(category -> new OrgRegistrationParticipantRequest(
                                category,
                                List.of(new SouvenirJson(souvenirId, "S")),
                                "단체원" + UUID.randomUUID()
                                        .toString().substring(0, 8),
                                "010-0000-0000",
                                "1990-01-01",
                                GenderClass.M
                        ))
                        .toList();

        return organizations.register(
                eventId,
                new OrgRegistrationCreateRequest(
                        new OrgAccountRequest(
                                "테스트 단체",
                                "g" + UUID.randomUUID()
                                        .toString().replace("-", "").substring(0, 15),
                                "Test1234!"
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
     * DB에 저장된 주문정보로 승인 요청을 생성한다.
     *
     * 결제키는 실제 PG 키가 아닌 테스트용 난수다.
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
     * 중복 완료 반영 검증에 사용할 기존 승인 Context를 DB에서 복원한다.
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
                "duplicate-test"
        );
    }

    /**
     * 외부 호출 시점에 로컬 Tx가 종료되었음을 검증하고 승인 성공을 반환한다.
     */
    protected void mockApprovalSuccess() {
        when(toss.confirm(any(TossPaymentConfirmRequest.class), anyString()))
                .thenAnswer(invocation -> {
                    assertThat(
                            TransactionSynchronizationManager
                                    .isActualTransactionActive()
                    ).isFalse();

                    TossPaymentConfirmRequest request = invocation.getArgument(0);

                    assertThat(s(
                            "select process_status from payment where order_id = ?",
                            request.orderId()
                    )).isEqualTo("CONFIRMING");

                    return approved(
                            request.paymentKey(),
                            request.orderId(),
                            request.amount()
                    );
                });
    }

    /**
     * 승인 성공 DTO를 생성한다. 외부 네트워크 호출은 수행하지 않는다.
     */
    protected TossPaymentConfirmResponse approved(
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
     * DB에서 정수 결과 한 개를 조회한다.
     */
    protected int n(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    /**
     * DB에서 문자열 결과 한 개를 조회한다.
     */
    protected String s(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    /**
     * 임시 확보와 확정 카운터를 실제 DB 값으로 검증한다.
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
     * 신청의 예약 상태·확보 회차·이력 개수 및 무기한 확보를 확인한다.
     */
    protected void reservation(
            String registrationId,
            String state,
            int sequence,
            int historySize
    ) {
        assertThat(s(
                "select status from reservation where registration_id = ?",
                registrationId
        )).isEqualTo(state);

        assertThat(n(
                "select hold_sequence from reservation where registration_id = ?",
                registrationId
        )).isEqualTo(sequence);

        assertThat(n(
                "select json_length(history) from reservation where registration_id = ?",
                registrationId
        )).isEqualTo(historySize);

        assertThat(n(
                """
                select count(*) from reservation
                where registration_id = ? and expires_at is null
                """,
                registrationId
        )).isEqualTo(1);
    }

    /**
     * 현재 예약 상세의 식별자를 조회하여 재확보 시 교체 여부를 비교한다.
     */
    protected List<String> itemIds(String registrationId) {
        return jdbc.queryForList(
                """
                select i.id
                from reservation_item i
                join reservation r on r.id = i.reservation_id
                where r.registration_id = ?
                order by i.id
                """,
                String.class,
                registrationId
        );
    }

    /**
     * CustomException의 종류뿐 아니라 실제 ErrorCode까지 검증한다.
     */
    protected void expectError(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        CustomException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(code)
                );
    }

    /**
     * 현재 테스트 대회의 개인·단체 신청이 모두 롤백되었는지 확인한다.
     */
    protected void noApplications() {
        assertThat(n(
                "select count(*) from registration where event_id = ?", eventId
        )).isZero();

        assertThat(n(
                "select count(*) from organization where event_id = ?", eventId
        )).isZero();

        assertThat(n(
                """
                select count(*)
                from reservation_item i
                join capacity c on c.id = i.capacity_id
                where c.event_id = ?
                """,
                eventId
        )).isZero();
    }

    /**
     * 지정 Payment에 저장된 PaymentAllocation 개수를 조회한다.
     *
     * @param paymentId 조회할 Payment
     * @return Allocation 행 개수
     */
    protected int allocationCount(
            String paymentId
    ) {
        return n(
                """
                select count(*)
                from payment_allocation
                where payment_id = ?
                """,
                paymentId
        );
    }


    /**
     * 지정 Payment의 전체 귀속금액 합계를 조회한다.
     *
     * @param paymentId 조회할 Payment
     * @return Allocation 합계
     */
    protected BigDecimal allocationSum(
            String paymentId
    ) {
        return jdbc.queryForObject(
                """
                select coalesce(sum(allocated_amount), 0)
                from payment_allocation
                where payment_id = ?
                """,
                BigDecimal.class,
                paymentId
        );
    }


    /**
     * 지정 Payment에서 특정 Registration에 귀속된 금액을 조회한다.
     *
     * @param paymentId Payment ID
     * @param registrationId Registration ID
     * @return 해당 Registration 귀속금액
     */
    protected BigDecimal allocationAmount(
            String paymentId,
            String registrationId
    ) {
        return jdbc.queryForObject(
                """
                select allocated_amount
                from payment_allocation
                where payment_id = ?
                  and registration_id = ?
                """,
                BigDecimal.class,
                paymentId,
                registrationId
        );
    }


    /**
     * 한 Registration에 과거부터 현재까지 연결된
     * 전체 PaymentAllocation 개수를 조회한다.
     *
     * 재결제 시 기존 Allocation이 삭제되지 않고
     * 새 Payment용 Allocation이 추가되는지 검증할 때 사용한다.
     *
     * @param registrationId Registration ID
     * @return 누적 Allocation 개수
     */
    protected int allocationCountForRegistration(
            String registrationId
    ) {
        return n(
                """
                select count(*)
                from payment_allocation
                where registration_id = ?
                """,
                registrationId
        );
    }

    /**
     * 이 테스트에서 생성한 데이터만 외래 키 참조의 역순으로 삭제한다.
     *
     * 정책 원본 및 다른 대회의 신청·결제 데이터는 보존한다.
     */
    @AfterEach
    void cleanupMvpFixtures() {
        tx.executeWithoutResult(status -> {
            String paymentTargets = """
                    registration_id in (
                        select id from registration where event_id = ?
                    )
                    or organization_id in (
                        select id from organization where event_id = ?
                    )
                    """;

            jdbc.update(
                    "delete from payment_process_log where payment_id in "
                            + "(select id from payment where "
                            + paymentTargets + ")",
                    eventId, eventId
            );

            jdbc.update(
                    """
                    delete from payment_allocation
                    where payment_id in (
                        select id
                        from payment
                        where registration_id in (
                            select id
                            from registration
                            where event_id = ?
                        )
                        or organization_id in (
                            select id
                            from organization
                            where event_id = ?
                        )
                    )
                    """,
                    eventId,
                    eventId
            );

            jdbc.update(
                    "delete from payment where " + paymentTargets,
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