package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityRequirementDiff;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.*;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationModificationParticipantRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.OrgRegistrationCreateResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.RegistrationCreateResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.service.*;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.*;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.AdditionalPaymentTargetResolver;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentAllocationCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentConfirmRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 실제 DB에서 신청 수정의 저장 결과·롤백·동시 요청을 검증한다.
 *
 * 기존 지원 클래스의 테스트 전용 대회와 Mock Toss를 사용한다.
 * 각 수정 요청은 실제 CommandService의 트랜잭션으로 실행한다.
 */
@Import({
        RegistrationInformationPolicyValidator.class, OrgRegistrationPersonalInformationValidator.class,
        OrgRegistrationPersonalInformationService.class, OrgRegistrationModificationGuard.class,

        AdditionalPaymentTargetResolver.class,
        CapacityRequirementResolver.class,
        ReservationCapacityDiffService.class,
        CapacityModificationService.class,
        ReservationRemovalService.class,

        RegistrationModificationAccessValidator.class,
        OrgRegistrationModificationAccessValidator.class,
        RegistrationModificationCandidateValidator.class,
        OrgRegistrationModificationCandidateValidator.class,

        RegistrationModificationPricingService.class,
        RegistrationModificationPaymentGuard.class,
        RegistrationPersonalModificationService.class,
        OrgRegistrationModificationService.class,
        RegistrationModificationSettlementService.class,
        RegistrationModificationClassifier.class,
        RegistrationPersonalInformationService.class,
        RegistrationPersonalInformationValidator.class,
        RegistrationUniqueInfoValidator.class,
        RegistrationModificationTransactionService.class,
        org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration.class
})
class RegistrationModificationDatabaseTest
        extends CapacityMvpTestSupport {

    @Autowired
    private RegistrationModificationTransactionService  modifications;

    @Autowired
    private RegistrationModificationTransactionService  modificationRaceCommands;

    @Autowired
    private RegistrationCapacityService staleVersionRegistrationCapacityService;

    @Autowired
    private ReservationCapacityDiffService staleVersionDiffService;

    @Autowired
    private CapacityModificationService staleVersionCapacityModificationService;

    @MockitoSpyBean
    private kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentCancelAllocationCreator
            cancelAllocationCreator;


    /**
     * 개인 미결제 신청의 종목·사이즈·금액을 변경하고
     * 기존 주문 보존 및 새 주문·Allocation 생성을 확인한다.
     */
    @Test
    void unpaidModificationReplacesOrderAndMovesCapacity() {
        RegistrationCreateResponse original =
                personal(categoryA, "S", "1990-01-01");

        changeCategoryPrice(categoryB, "60000");

        RegistrationModificationSettlementResult result =
                modifications.modifyPersonal(
                        eventId,
                        original.registrationId(),
                        personalRequest(
                                original.registrationId(),
                                categoryB,
                                "M"
                        )
                );

        assertThat(result.orders()).hasSize(1);

        String newPaymentId = result.orders().get(0).paymentId();

        assertThat(newPaymentId).isNotEqualTo(original.paymentId());
        assertThat(paymentState(original.paymentId()))
                .isEqualTo("INVALIDATED");
        assertThat(paymentState(newPaymentId)).isEqualTo("READY");

        assertThat(registrationState(original.registrationId()))
                .isEqualTo("PAYMENT_PENDING");

        assertThat(money(
                "select contract_amount from registration where id = ?",
                original.registrationId()
        )).isEqualByComparingTo("60000");

        assertThat(allocationSum(original.paymentId()))
                .isEqualByComparingTo("40000");
        assertThat(allocationSum(newPaymentId))
                .isEqualByComparingTo("60000");
        assertThat(allocationCount(newPaymentId)).isEqualTo(1);

        assertThat(allocationAmount(
                newPaymentId,
                original.registrationId()
        )).isEqualByComparingTo("60000");

        counters(total, 1, 0);
        counters(categoryACapacity, 0, 0);
        counters(categoryBCapacity, 1, 0);
        counters(shirtS, 0, 0);
        counters(shirtM, 1, 0);
    }

    /**
     * 새 사이즈 확보 실패 시 기존 신청·주문·예약·카운터를 보존한다.
     *
     * 앞서 수행한 READY 무효화와 예약 이력 변경도 롤백되어야 한다.
     */
    @Test
    void capacityFailureRollsBackWholeModification() {
        RegistrationCreateResponse original =
                personal(categoryA, "S", "1990-01-01");

        limit(shirtM, 0);

        Map<String, List<Map<String, Object>>> before =
                snapshot();

        expectError(
                ErrorCode.CAPACITY_ACQUIRE_FAILED,
                () -> modifications.modifyPersonal(
                        eventId,
                        original.registrationId(),
                        personalRequest(
                                original.registrationId(),
                                categoryB,
                                "M"
                        )
                )
        );

        assertThat(snapshot()).isEqualTo(before);
    }

    /**
     * 새 Allocation까지 실제 저장한 뒤 실패시켜 전체 롤백을 확인한다.
     *
     * 신규 Payment뿐 아니라 앞선 Capacity 이동과 기존 주문 무효화도 원복한다.
     */
    @Test
    void allocationFailureRollsBackWholeModification() {
        RegistrationCreateResponse original =
                personal(categoryA, "S", "1990-01-01");

        changeCategoryPrice(categoryB, "60000");

        Map<String, List<Map<String, Object>>> before =
                snapshot();

        failAfterAllocationWrite();

        expectError(
                ErrorCode.PAYMENT_ALLOCATION_INTEGRITY_ERROR,
                () -> modifications.modifyPersonal(
                        eventId,
                        original.registrationId(),
                        personalRequest(
                                original.registrationId(),
                                categoryB,
                                "M"
                        )
                )
        );

        assertThat(snapshot()).isEqualTo(before);
    }

    /**
     * 실제 승인된 신청을 수정하여 차액별 상태와 확정 재고 이동을 검증한다.
     *
     * 추가 결제·환불을 실행하거나 기존 승인금액을 변경하지 않는다.
     */
    @ParameterizedTest
    @CsvSource({
            "60000, ADDITIONAL_PAYMENT_REQUIRED, 20000",
            "40000, CONFIRMED, 0",
            "30000, PARTIAL_REFUND_REQUIRED, -10000"
    })
    void paidModificationKeepsFinancialHistory(
            String newAmount,
            String expectedStatus,
            String expectedBalance
    ) {
        RegistrationCreateResponse original =
                personal(categoryA, "S", "1990-01-01");

        mockApprovalSuccess();
        payments.confirm(confirmRequest(original.paymentId()));

        changeCategoryPrice(categoryB, newAmount);

        RegistrationModificationSettlementResult result =
                modifications.modifyPersonal(
                        eventId,
                        original.registrationId(),
                        personalRequest(
                                original.registrationId(),
                                categoryB,
                                "M"
                        )
                );

        if (new BigDecimal(expectedBalance).signum() > 0) {
            assertThat(result.orders()).hasSize(1);
            String additionalPaymentId = result.orders().get(0).paymentId();
            assertThat(result.orders().get(0).amount()).isEqualByComparingTo(expectedBalance);
            assertThat(paymentState(additionalPaymentId)).isEqualTo("READY");
            assertThat(s("select purpose from payment where id = ?", additionalPaymentId))
                    .isEqualTo("ADDITIONAL_PAYMENT");
            assertThat(allocationSum(additionalPaymentId)).isEqualByComparingTo(expectedBalance);
            assertThat(money("select allocated_amount from payment_allocation where payment_id = ? and registration_id = ?",
                    additionalPaymentId, original.registrationId()))
                    .isEqualByComparingTo(expectedBalance);
        } else {
            assertThat(result.orders()).isEmpty();
        }
        assertThat(result.members()).hasSize(1);

        assertThat(result.members().get(0).balance())
                .isEqualByComparingTo(expectedBalance);

        assertThat(registrationState(original.registrationId()))
                .isEqualTo(expectedStatus);

        assertThat(money(
                "select paid_amount from registration where id = ?",
                original.registrationId()
        )).isEqualByComparingTo("40000");

        assertThat(paymentState(original.paymentId()))
                .isEqualTo("COMPLETED");

        assertThat(allocationSum(original.paymentId()))
                .isEqualByComparingTo("40000");

        counters(total, 0, 1);
        counters(categoryACapacity, 0, 0);
        counters(categoryBCapacity, 0, 1);
        counters(shirtS, 0, 0);
        counters(shirtM, 0, 1);
    }

    /** 추가 결제 귀속 저장 실패 시 신청·예약·정원과 새 주문을 함께 롤백한다. */
    @Test
    void additionalAllocationFailureRollsBackWholeModification() {
        RegistrationCreateResponse original = personal(categoryA, "S", "1990-01-01");
        mockApprovalSuccess();
        payments.confirm(confirmRequest(original.paymentId()));
        changeCategoryPrice(categoryB, "60000");
        Map<String, List<Map<String, Object>>> before = snapshot();
        failAfterAllocationWrite();

        expectError(ErrorCode.PAYMENT_ALLOCATION_INTEGRITY_ERROR,
                () -> modifications.modifyPersonal(eventId, original.registrationId(),
                        personalRequest(original.registrationId(), categoryB, "M")));

        assertThat(snapshot()).isEqualTo(before);
    }

    /**
     * 수정 후 최초 참가비가 0원이면 주문 없이 예약과 재고를 확정한다.
     */
    @Test
    void zeroAmountModificationConfirmsWithoutPayment() {
        RegistrationCreateResponse original =
                personal(categoryA, "S", "1990-01-01");

        changeCategoryPrice(categoryB, "0");

        RegistrationModificationSettlementResult result =
                modifications.modifyPersonal(
                        eventId,
                        original.registrationId(),
                        personalRequest(
                                original.registrationId(),
                                categoryB,
                                "M"
                        )
                );

        assertThat(result.orders()).isEmpty();
        assertThat(result.members().get(0).balance())
                .isEqualByComparingTo("0");

        assertThat(registrationState(original.registrationId()))
                .isEqualTo("CONFIRMED");

        assertThat(s(
                "select status from reservation where registration_id = ?",
                original.registrationId()
        )).isEqualTo("CONSUMED");

        assertThat(paymentState(original.paymentId()))
                .isEqualTo("INVALIDATED");

        assertThat(n(
                "select count(*) from payment where registration_id = ?",
                original.registrationId()
        )).isEqualTo(1);

        counters(total, 0, 1);
        counters(categoryBCapacity, 0, 1);
        counters(shirtS, 0, 0);
        counters(shirtM, 0, 1);

        verifyNoInteractions(toss);
    }

    /**
     * 결제 완료 단체에서 기존 구성원 수정·제거·신규 추가를 함께 처리한다.
     *
     * 환불 대상 금액과 신규 참가비를 상쇄하지 않고,
     * 새 최초 주문에는 신규 참가자만 귀속한다.
     */
    @Test
    void paidGroupModificationDoesNotOffsetRefundAndNewPayment() {
        OrgRegistrationCreateResponse original =
                group(categoryA, categoryA);

        mockApprovalSuccess();
        payments.confirm(confirmRequest(original.paymentId()));

        changeCategoryPrice(categoryB, "30000");

        String retainedId = original.registrationIds().get(0);
        String removedId = original.registrationIds().get(1);

        RegistrationModificationSettlementResult result =
                modifications.modifyOrganization(
                        eventId,
                        original.organizationId(),
                        groupRequest(original.organizationId(), retainedId)
                );

        assertThat(result.members()).hasSize(3);
        assertThat(result.orders()).hasSize(1);

        String addedId = result.members().stream()
                .map(RegistrationModificationSettlementResult.Member::registrationId)
                .filter(id -> !original.registrationIds().contains(id))
                .findFirst()
                .orElseThrow();

        String newPaymentId = result.orders().get(0).paymentId();

        assertThat(registrationState(retainedId))
                .isEqualTo("PARTIAL_REFUND_REQUIRED");
        assertThat(registrationState(removedId))
                .isEqualTo("CANCELLATION_PENDING");
        assertThat(registrationState(addedId))
                .isEqualTo("PAYMENT_PENDING");

        assertThat(n(
                "select count(*) from registration where id = ? and is_del = true",
                removedId
        )).isEqualTo(1);

        assertThat(money(
                "select paid_amount from registration where id = ?",
                removedId
        )).isEqualByComparingTo("40000");

        assertThat(s(
                "select status from reservation where registration_id = ?",
                removedId
        )).isEqualTo("RELEASED");

        assertThat(result.orders().get(0).amount())
                .isEqualByComparingTo("40000");

        assertThat(allocationCount(newPaymentId)).isEqualTo(1);
        assertThat(allocationAmount(newPaymentId, addedId))
                .isEqualByComparingTo("40000");

        assertThat(allocationCount(original.paymentId())).isEqualTo(2);
        assertThat(allocationSum(original.paymentId()))
                .isEqualByComparingTo("80000");

        assertThat(paymentState(original.paymentId()))
                .isEqualTo("COMPLETED");




        counters(total, 1, 1);
        counters(categoryACapacity, 1, 0);
        counters(categoryBCapacity, 0, 1);
        counters(shirtS, 1, 0);
        counters(shirtM, 0, 1);


        assertThat(result.refunds()).hasSize(1);
        String cancelId = result.refunds().get(0).paymentCancelId();
        assertThat(result.refunds().get(0).amount()).isEqualByComparingTo("50000");
        assertThat(money(
                """
                select ca.allocated_amount from payment_cancel_allocation ca
                join payment_allocation a on a.id = ca.payment_allocation_id
                where ca.payment_cancel_id = ? and a.registration_id = ?
                """, cancelId, retainedId)).isEqualByComparingTo("10000");
        assertThat(money(
                """
                select ca.allocated_amount from payment_cancel_allocation ca
                join payment_allocation a on a.id = ca.payment_allocation_id
                where ca.payment_cancel_id = ? and a.registration_id = ?
                """, cancelId, removedId)).isEqualByComparingTo("40000");
    }

    /**
     * 단체 추가·기존 수정·제거 이후 마지막 Allocation 저장이 실패하면
     * 제거 표시와 신규 신청을 포함하여 전체를 원복한다.
     */
    @Test
    void groupLateFailureRollsBackAddedModifiedAndRemovedMembers() {
        OrgRegistrationCreateResponse original =
                group(categoryA, categoryA);

        String retainedId = original.registrationIds().get(0);

        Map<String, List<Map<String, Object>>> before =
                snapshot();

        failAfterAllocationWrite();

        expectError(
                ErrorCode.PAYMENT_ALLOCATION_INTEGRITY_ERROR,
                () -> modifications.modifyOrganization(
                        eventId,
                        original.organizationId(),
                        groupRequest(original.organizationId(), retainedId)
                )
        );

        assertThat(snapshot()).isEqualTo(before);
    }

    /**
     * 진행 중 또는 결과 불명 Payment가 실제 DB에 있으면 수정 전체를 차단한다.
     *
     * 이 테스트는 상태 차단 검증이며 실제 승인 경합 재현과는 구분한다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"CONFIRMING", "UNKNOWN"})
    void unsettledPaymentBlocksModification(String status) {
        RegistrationCreateResponse original =
                personal(categoryA, "S", "1990-01-01");

        jdbc.update(
                "update payment set process_status = ? where id = ?",
                status,
                original.paymentId()
        );

        Map<String, List<Map<String, Object>>> before =
                snapshot();

        expectError(
                ErrorCode.REGISTRATION_MODIFICATION_PAYMENT_CONFLICT,
                () -> modifications.modifyPersonal(
                        eventId,
                        original.registrationId(),
                        personalRequest(
                                original.registrationId(),
                                categoryB,
                                "M"
                        )
                )
        );

        assertThat(snapshot()).isEqualTo(before);
    }

    /**
     * 마지막 M 사이즈 한 개에 두 수정 요청이 동시에 접근해도
     * 한 요청만 성공하며 패배한 요청은 기존 권리를 유지한다.
     *
     * 현재 Event 잠금까지 포함한 전체 수정 경로를 검증한다.
     * Capacity UPDATE 단독 경합 시험을 대신하지 않는다.
     */
    @Test
    void onlyOneModificationGetsLastSize() throws Exception {
        RegistrationCreateResponse first =
                personal(categoryA, "S", "1990-01-01");

        RegistrationCreateResponse second =
                personal(categoryA, "S", "1990-01-01");

        RegistrationModificationRequest firstRequest =
                personalRequest(first.registrationId(), categoryA, "M");

        RegistrationModificationRequest secondRequest =
                personalRequest(second.registrationId(), categoryA, "M");

        limit(shirtM, 1);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> firstResult = executor.submit(
                    () -> attemptModification(
                            first.registrationId(),
                            firstRequest,
                            ready,
                            start
                    )
            );

            Future<Boolean> secondResult = executor.submit(
                    () -> attemptModification(
                            second.registrationId(),
                            secondRequest,
                            ready,
                            start
                    )
            );

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            boolean firstSucceeded =
                    firstResult.get(30, TimeUnit.SECONDS);

            boolean secondSucceeded =
                    secondResult.get(30, TimeUnit.SECONDS);

            assertThat(firstSucceeded ^ secondSucceeded).isTrue();

            String winnerPaymentId = firstSucceeded
                    ? first.paymentId()
                    : second.paymentId();

            String loserPaymentId = firstSucceeded
                    ? second.paymentId()
                    : first.paymentId();

            assertThat(paymentState(winnerPaymentId))
                    .isEqualTo("INVALIDATED");
            assertThat(paymentState(loserPaymentId))
                    .isEqualTo("READY");

            counters(total, 2, 0);
            counters(categoryACapacity, 2, 0);
            counters(shirtS, 1, 0);
            counters(shirtM, 1, 0);

            assertThat(n(
                    """
                    select count(*)
                    from payment p
                    join registration r on r.id = p.registration_id
                    where r.event_id = ?
                    """,
                    eventId
            )).isEqualTo(3);

            assertThat(n(
                    """
                    select sum(json_length(rv.history))
                    from reservation rv
                    join registration r on r.id = rv.registration_id
                    where r.event_id = ?
                    """,
                    eventId
            )).isEqualTo(3);
        } finally {
            start.countDown();
            executor.shutdownNow();

            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS))
                    .as("테스트 데이터 정리 전에 작업 스레드가 종료되어야 합니다.")
                    .isTrue();
        }
    }

    /**
     * 기존 주문의 승인 시작과 신청 수정이 경합할 때
     * 하나만 성공하고 다른 작업은 해당 상태에 맞게 차단되는지 검증한다.
     *
     * 외부 PG 승인 없이 실제 로컬 승인 시작 트랜잭션을 사용한다.
     */
    @Test
    void approvalStartAndModificationCannotBothSucceed() throws Exception {
        RegistrationCreateResponse created =
                personal(categoryA, "S", "1990-01-01");

        String registrationId = created.registrationId();
        String oldPaymentId = created.paymentId();

        PaymentConfirmRequest approvalRequest =
                confirmRequest(oldPaymentId);

        RegistrationModificationRequest modificationRequest =
                raceModificationRequest(registrationId);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<ErrorCode> approvalFuture = executor.submit(() ->
                    runModificationRaceAction(
                            ready,
                            start,
                            () -> paymentTransactions.beginConfirm(
                                    approvalRequest,
                                    UUID.randomUUID().toString(),
                                    NOW
                            )
                    )
            );

            Future<ErrorCode> modificationFuture = executor.submit(() ->
                    runModificationRaceAction(
                            ready,
                            start,
                            () -> modificationRaceCommands.modifyPersonal(
                                    eventId,
                                    registrationId,
                                    modificationRequest
                            )
                    )
            );

            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .as("두 작업이 실행 준비를 마쳐야 합니다.")
                    .isTrue();

            start.countDown();

            ErrorCode approvalError =
                    approvalFuture.get(30, TimeUnit.SECONDS);

            ErrorCode modificationError =
                    modificationFuture.get(30, TimeUnit.SECONDS);

            assertThat(
                    (approvalError == null)
                            ^ (modificationError == null)
            )
                    .as("승인 시작과 신청 수정 중 정확히 하나만 성공해야 합니다.")
                    .isTrue();

            if (approvalError == null) {
                assertThat(modificationError)
                        .isEqualTo(
                                ErrorCode.REGISTRATION_MODIFICATION_PAYMENT_CONFLICT
                        );

                assertThat(s(
                        "select process_status from payment where id = ?",
                        oldPaymentId
                )).isEqualTo("CONFIRMING");

                assertThat(s(
                        "select status from reservation where registration_id = ?",
                        registrationId
                )).isEqualTo("PROCESSING");

                assertThat(s(
                        "select event_category_id from registration where id = ?",
                        registrationId
                )).isEqualTo(categoryA);

                assertThat(n(
                        "select count(*) from payment where registration_id = ?",
                        registrationId
                )).isEqualTo(1);

                counters(categoryACapacity, 1, 0);
                counters(categoryBCapacity, 0, 0);
                counters(shirtS, 1, 0);
                counters(shirtM, 0, 0);
            } else {
                assertThat(approvalError)
                        .isEqualTo(ErrorCode.PAYMENT_NOT_CONFIRMABLE);

                assertThat(s(
                        "select process_status from payment where id = ?",
                        oldPaymentId
                )).isEqualTo("INVALIDATED");

                assertThat(s(
                        "select status from reservation where registration_id = ?",
                        registrationId
                )).isEqualTo("HELD");

                assertThat(s(
                        "select event_category_id from registration where id = ?",
                        registrationId
                )).isEqualTo(categoryB);

                assertThat(n(
                        "select count(*) from payment where registration_id = ?",
                        registrationId
                )).isEqualTo(2);

                assertThat(n(
                        """
                        select count(*)
                        from payment
                        where registration_id = ?
                          and process_status = 'READY'
                          and id <> ?
                        """,
                        registrationId,
                        oldPaymentId
                )).isEqualTo(1);

                counters(categoryACapacity, 0, 0);
                counters(categoryBCapacity, 1, 0);
                counters(shirtS, 0, 0);
                counters(shirtM, 1, 0);
            }

            counters(total, 1, 0);

            assertThat(jdbc.queryForObject(
                    "select paid_amount from registration where id = ?",
                    BigDecimal.class,
                    registrationId
            )).isEqualByComparingTo(BigDecimal.ZERO);

            verifyNoInteractions(toss);
        } finally {
            start.countDown();
            executor.shutdownNow();

            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS))
                    .as("정리 작업 전에 경합 테스트의 스레드가 종료되어야 합니다.")
                    .isTrue();
        }
    }

    /** 가격 인하 수정 한 번으로 환불 원장까지 저장하되 실제 돈은 차감하지 않는다. */
    @Test
    void priceReductionPreparesRefundWithoutCallingToss() {
        RegistrationCreateResponse original = personal(categoryA, "S", "1990-01-01");
        mockApprovalSuccess();
        payments.confirm(confirmRequest(original.paymentId()));
        clearInvocations(toss);
        changeCategoryPrice(categoryB, "30000");

        RegistrationModificationSettlementResult result = modifications.modifyPersonal(
                eventId, original.registrationId(), personalRequest(original.registrationId(), categoryB, "M"));

        assertThat(result.refunds()).hasSize(1);
        String cancelId = result.refunds().get(0).paymentCancelId();
        assertThat(result.refunds().get(0).amount()).isEqualByComparingTo("10000");
        assertThat(s("select status from payment_cancel where id = ?", cancelId)).isEqualTo("PROCESSING");
        assertThat(s("select cancel_type from payment_cancel where id = ?", cancelId)).isEqualTo("PARTIAL");
        assertThat(money("select paid_amount from registration where id = ?", original.registrationId()))
                .isEqualByComparingTo("40000");
        assertThat(money("select sum(allocated_amount) from payment_cancel_allocation where payment_cancel_id = ?", cancelId))
                .isEqualByComparingTo("10000");
        assertThat(n("select count(*) from payment_process_log where payment_cancel_id = ? and process_type = 'CANCEL_PREPARED'", cancelId))
                .isEqualTo(1);
        assertThat(s("select status from reservation where registration_id = ?", original.registrationId())).isEqualTo("CONSUMED");
        verifyNoInteractions(toss);
    }

    /** 환불 진행·결과불명 동안 재수정은 새 금융 기록과 자원 변경을 남기지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"PROCESSING", "UNKNOWN"})
    void unsettledRefundBlocksNextFullModification(String status) {
        RegistrationCreateResponse original = personal(categoryA, "S", "1990-01-01");
        mockApprovalSuccess();
        payments.confirm(confirmRequest(original.paymentId()));
        changeCategoryPrice(categoryB, "30000");
        RegistrationModificationSettlementResult first = modifications.modifyPersonal(
                eventId, original.registrationId(), personalRequest(original.registrationId(), categoryB, "M"));
        String cancelId = first.refunds().get(0).paymentCancelId();
        jdbc.update("update payment_cancel set status = ? where id = ?", status, cancelId);
        Map<String, List<Map<String, Object>>> before = snapshot();

        expectError(ErrorCode.PAYMENT_CANCEL_CONFLICT,
                () -> modifications.modifyPersonal(eventId, original.registrationId(),
                        personalRequest(original.registrationId(), categoryA, "S")));

        assertThat(snapshot()).isEqualTo(before);
    }

    /** 환불 귀속까지 저장한 뒤 실패하면 수정·정원·환불 원장을 함께 롤백한다. */
    @Test
    void refundAllocationFailureRollsBackWholeModification() {
        RegistrationCreateResponse original = personal(categoryA, "S", "1990-01-01");
        mockApprovalSuccess();
        payments.confirm(confirmRequest(original.paymentId()));
        changeCategoryPrice(categoryB, "30000");
        Map<String, List<Map<String, Object>>> before = snapshot();
        kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentCancelAllocationCreator spy =
                AopTestUtils.getUltimateTargetObject(cancelAllocationCreator);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            em.flush();
            throw new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
        }).when(spy).create(
                any(kr.co.teambrain.marvelrun.user.event.command.application.domain.PaymentCancel.class), anyList());

        expectError(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR,
                () -> modifications.modifyPersonal(eventId, original.registrationId(),
                        personalRequest(original.registrationId(), categoryB, "M")));

        assertThat(snapshot()).isEqualTo(before);
    }

    /**
     * 변경 계획 계산 이후 Reservation 버전이 달라졌다면
     * 과거 버전의 계획으로 Capacity를 이동할 수 없는지 검증한다.
     *
     * HELD와 CONSUMED를 각각 검증하며,
     * 버전 불일치로 거절된 작업이 DB에 추가 변경을 남기지 않아야 한다.
     */
    @ParameterizedTest(name = "결제 완료 여부={0}")
    @ValueSource(booleans = {false, true})
    void staleReservationVersionRejectsCapacityMove(
            boolean paid
    ) {
        RegistrationCreateResponse created =
                personal(categoryA, "S", "1990-01-01");

        String registrationId = created.registrationId();

        if (paid) {
            mockApprovalSuccess();

            payments.confirm(
                    confirmRequest(created.paymentId())
            );
        }

        String reservationId = s(
                "select id from reservation where registration_id = ?",
                registrationId
        );

        String expectedState = paid ? "CONSUMED" : "HELD";

        assertThat(s(
                "select status from reservation where id = ?",
                reservationId
        )).isEqualTo(expectedState);

        /*
         * 첫 번째 트랜잭션:
         * 현재 Reservation 버전을 담은 정상적인 변경 계획을 계산한다.
         *
         * 기존: 전체 1 + 종목 A 1 + 티셔츠 S 1
         * 변경: 전체 1 + 종목 B 1 + 티셔츠 M 1
         */
        List<CapacityRequirementDiff> staleDiffs =
                tx.execute(status -> {
                    staleVersionRegistrationCapacityService.lockEvent(
                            eventId
                    );

                    Reservation reservation =
                            em.find(
                                    Reservation.class,
                                    reservationId
                            );

                    assertThat(reservation).isNotNull();

                    Map<String, Integer> newRequirements = Map.of(
                            total, 1,
                            categoryBCapacity, 1,
                            shirtM, 1
                    );

                    return staleVersionDiffService.compareAll(
                            List.of(reservation),
                            Map.of(
                                    reservationId,
                                    newRequirements
                            )
                    );
                });

        assertThat(staleDiffs).hasSize(1);

        Long oldVersion =
                staleDiffs.get(0).reservationVersion();

        assertThat(oldVersion).isNotNull();

        /*
         * 두 번째 트랜잭션:
         * 다른 처리가 먼저 커밋한 상황을 재현한다.
         *
         * 상태와 수량을 유지하고 버전만 바꿔서
         * 이후 실패 원인을 버전 불일치로 한정한다.
         * 테스트용 SQL이며 실제 업무 처리 방식은 아니다.
         */
        tx.executeWithoutResult(status -> {
            staleVersionRegistrationCapacityService.lockEvent(
                    eventId
            );

            int updated = jdbc.update(
                    """
                    update reservation
                    set version = version + 1
                    where id = ?
                      and version = ?
                    """,
                    reservationId,
                    oldVersion
            );

            assertThat(updated).isEqualTo(1);
        });

        assertThat(jdbc.queryForObject(
                "select version from reservation where id = ?",
                Long.class,
                reservationId
        )).isEqualTo(oldVersion + 1L);

        /*
         * 비교 기준은 다른 트랜잭션의 변경이 커밋된 이후다.
         * 거절된 작업이 그 변경을 되돌리거나 추가 변경을 남겨서는 안 된다.
         */
        Map<String, List<Map<String, Object>>> beforeRejectedMove =
                snapshot();

        /*
         * 세 번째 트랜잭션:
         * 과거 버전이 담긴 계획으로 이동을 시도한다.
         *
         * 예외 검증은 트랜잭션 바깥에서 수행하여
         * 실패한 트랜잭션이 종료된 뒤 DB 상태를 비교한다.
         */
        expectError(
                ErrorCode.RESERVATION_STATE_CONFLICT,
                () -> tx.executeWithoutResult(status -> {
                    staleVersionRegistrationCapacityService.lockEvent(
                            eventId
                    );

                    staleVersionCapacityModificationService.moveAll(
                            eventId,
                            staleDiffs,
                            NOW
                    );

                    em.flush();
                })
        );

        assertThat(snapshot())
                .isEqualTo(beforeRejectedMove);

        int expectedHeld = paid ? 0 : 1;
        int expectedConfirmed = paid ? 1 : 0;

        counters(total, expectedHeld, expectedConfirmed);
        counters(categoryACapacity, expectedHeld, expectedConfirmed);
        counters(shirtS, expectedHeld, expectedConfirmed);

        counters(categoryBCapacity, 0, 0);
        counters(shirtM, 0, 0);

        assertThat(s(
                "select status from reservation where id = ?",
                reservationId
        )).isEqualTo(expectedState);

        assertThat(jdbc.queryForObject(
                "select version from reservation where id = ?",
                Long.class,
                reservationId
        )).isEqualTo(oldVersion + 1L);
    }

    /**
     * 두 작업의 출발 시점을 맞추고 업무상 거절 결과를 반환한다.
     *
     * 성공하면 null을 반환한다.
     * DB 오류, 데드락, 시간 초과 등은 삼키지 않고 테스트 실패로 전달한다.
     */
    private ErrorCode runModificationRaceAction(
            CountDownLatch ready,
            CountDownLatch start,
            Runnable action
    ) throws InterruptedException {
        ready.countDown();

        assertThat(start.await(10, TimeUnit.SECONDS))
                .as("경합 작업의 시작 신호가 전달되어야 합니다.")
                .isTrue();

        try {
            action.run();
            return null;
        } catch (CustomException exception) {
            return exception.getErrorCode();
        }
    }

    /**
     * 기존 본인확인 정보를 유지하면서
     * 종목을 B, 기념품 사이즈를 M으로 변경하는 경합용 요청을 생성한다.
     */
    private RegistrationModificationRequest raceModificationRequest(
            String registrationId
    ) {
        String name = s(
                "select name from registration where id = ?",
                registrationId
        );

        String birth = s(
                "select birth from registration where id = ?",
                registrationId
        );

        String phNum = s(
                "select ph_num from registration where id = ?",
                registrationId
        );

        return new RegistrationModificationRequest(
                new RegistrationAccessRequest(
                        name,
                        birth,
                        phNum,
                        "Test1234!"
                ),
                categoryB,
                List.of(new SouvenirJson(souvenirId, "M")),
                name,
                phNum,
                birth,
                GenderClass.M,
                "경합 테스트 주소",
                "상세",
                "테스트 보호자",
                true
        );
    }

    /**
     * 두 요청의 출발을 맞추고 수량 부족만 정상적인 경합 실패로 인정한다.
     *
     * 교착·시간 초과·기타 예외는 숨기지 않고 테스트 실패로 전달한다.
     */
    private boolean attemptModification(
            String registrationId,
            RegistrationModificationRequest request,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();

        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();

        try {
            modifications.modifyPersonal(
                    eventId,
                    registrationId,
                    request
            );

            return true;
        } catch (CustomException exception) {
            if (exception.getErrorCode() != ErrorCode.CAPACITY_ACQUIRE_FAILED) {
                throw exception;
            }

            return false;
        }
    }

    /**
     * 현재 신청의 본인확인 정보를 사용하여 종목·사이즈 수정 요청을 만든다.
     */
    private RegistrationModificationRequest personalRequest(
            String registrationId,
            String categoryId,
            String size
    ) {
        String name = s(
                "select name from registration where id = ?",
                registrationId
        );

        String birth = s(
                "select birth from registration where id = ?",
                registrationId
        );

        String phone = s(
                "select ph_num from registration where id = ?",
                registrationId
        );

        return new RegistrationModificationRequest(
                new RegistrationAccessRequest(
                        name,
                        birth,
                        phone,
                        "Test1234!"
                ),
                categoryId,
                List.of(new SouvenirJson(souvenirId, size)),
                name,
                phone,
                birth,
                GenderClass.M,
                "수정 주소",
                "수정 상세",
                "테스트 보호자",
                true
        );
    }

    /**
     * 기존 한 명은 B/M으로 수정하고 나머지는 제거하며 신규 한 명을 추가한다.
     */
    private OrgRegistrationModificationRequest groupRequest(
            String organizationId,
            String retainedId
    ) {
        String loginId = s(
                "select login_id from organization where id = ?",
                organizationId
        );

        return new OrgRegistrationModificationRequest(
                new OrganizationAccessRequest(loginId, "Test1234!"),
                List.of(
                        new OrgRegistrationModificationParticipantRequest(
                                retainedId,
                                categoryB,
                                List.of(new SouvenirJson(souvenirId, "M")),
                                s(
                                        "select name from registration where id = ?",
                                        retainedId
                                ),
                                "010-0000-0000",
                                "1990-01-01",
                                GenderClass.M
                        ),
                        new OrgRegistrationModificationParticipantRequest(
                                null,
                                categoryA,
                                List.of(new SouvenirJson(souvenirId, "S")),
                                "추가" + UUID.randomUUID()
                                        .toString().substring(0, 8),
                                "010-0000-0000",
                                "1990-01-01",
                                GenderClass.M
                        )
                )
        );
    }

    /**
     * 테스트 전용 종목의 가격을 변경한다.
     */
    private void changeCategoryPrice(String categoryId, String amount) {
        jdbc.update(
                "update event_category set amount = ? where id = ?",
                new BigDecimal(amount),
                categoryId
        );
    }

    /**
     * 실제 Allocation 저장 및 flush 이후 예외를 발생시켜
     * 신청 수정 트랜잭션 전체의 롤백을 검증한다.
     *
     * 스텁 설정 시에는 트랜잭션 프록시 내부의 Mockito Spy를 사용한다.
     */
    private void failAfterAllocationWrite() {
        PaymentAllocationCreator allocationCreatorSpy =
                AopTestUtils.getUltimateTargetObject(
                        paymentAllocationCreator
                );

        doAnswer(invocation -> {
            invocation.callRealMethod();

            em.flush();

            throw new CustomException(
                    ErrorCode.PAYMENT_ALLOCATION_INTEGRITY_ERROR,
                    "전체 롤백 검증을 위해 Allocation 저장 후 예외를 발생시킵니다."
            );
        }).when(allocationCreatorSpy).create(
                any(Payment.class),
                anyList()
        );
    }

    /**
     * 저장된 신청 상태를 조회한다.
     */
    private String registrationState(String registrationId) {
        return s(
                "select status from registration where id = ?",
                registrationId
        );
    }

    /**
     * 저장된 Payment 처리 상태를 조회한다.
     */
    private String paymentState(String paymentId) {
        return s(
                "select process_status from payment where id = ?",
                paymentId
        );
    }

    /**
     * 금액 컬럼을 BigDecimal로 조회한다.
     */
    private BigDecimal money(String sql, Object... args) {
        return jdbc.queryForObject(sql, BigDecimal.class, args);
    }

    /**
     * 롤백 검증에 필요한 신청·예약·상세·카운터·주문·귀속 상태를 복사한다.
     *
     * JSON은 문자열로 읽어 JDBC 반환 객체의 참조 비교를 피한다.
     * Payment는 Allocation 생성 여부와 무관하게 조회하여
     * Allocation 없는 신규 주문이 남는 오류도 발견한다.
     */
    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();

        result.put("registrations", jdbc.queryForList(
                """
                select id, event_category_id, organization_id,
                       name, ph_num, birth,
                       address, address_detail,
                       contract_amount, paid_amount, status, is_del, version,
                       cast(souvenir_json as char) as souvenirs
                from registration
                where event_id = ?
                order by id
                """,
                eventId
        ));

        result.put("reservations", jdbc.queryForList(
                """
                select rv.id, rv.registration_id, rv.status,
                       rv.version, rv.hold_sequence,
                       cast(rv.history as char) as history
                from reservation rv
                join registration r on r.id = rv.registration_id
                where r.event_id = ?
                order by rv.id
                """,
                eventId
        ));

        result.put("items", jdbc.queryForList(
                """
                select ri.id, ri.reservation_id, ri.capacity_id, ri.quantity
                from reservation_item ri
                join reservation rv on rv.id = ri.reservation_id
                join registration r on r.id = rv.registration_id
                where r.event_id = ?
                order by ri.id
                """,
                eventId
        ));

        result.put("capacities", jdbc.queryForList(
                """
                select id, held_count, confirmed_count, limit_count, active
                from capacity
                where event_id = ?
                order by id
                """,
                eventId
        ));

        result.put("payments", jdbc.queryForList(
                """
                select p.id, p.registration_id, p.organization_id,
                       p.order_id, p.amount, p.purpose, p.process_status
                from payment p
                where p.registration_id in (
                    select id from registration where event_id = ?
                )
                or p.organization_id in (
                    select id from organization where event_id = ?
                )
                order by p.id
                """,
                eventId,
                eventId
        ));

        result.put("allocations", jdbc.queryForList(
                """
                select pa.id, pa.payment_id, pa.registration_id,
                       pa.allocated_amount
                from payment_allocation pa
                join registration r on r.id = pa.registration_id
                where r.event_id = ?
                order by pa.id
                """,
                eventId
        ));

        result.put("event", jdbc.queryForList(
                """
                select id, event_status
                from event
                where id = ?
                """,
                eventId
        ));

        result.put("cancellations", jdbc.queryForList(
                """
                select c.id, c.payment_id, c.cancel_amount, c.cancel_type, c.purpose,
                       c.status, c.idempotency_key, c.requested_at, c.version
                from payment_cancel c join payment p on p.id = c.payment_id
                where p.registration_id in (select id from registration where event_id = ?)
                   or p.organization_id in (select id from organization where event_id = ?)
                order by c.id
                """, eventId, eventId));
        result.put("cancelAllocations", jdbc.queryForList(
                """
                select ca.id, ca.payment_cancel_id, ca.payment_allocation_id, ca.allocated_amount
                from payment_cancel_allocation ca
                join payment_cancel c on c.id = ca.payment_cancel_id
                join payment p on p.id = c.payment_id
                where p.registration_id in (select id from registration where event_id = ?)
                   or p.organization_id in (select id from organization where event_id = ?)
                order by ca.id
                """, eventId, eventId));
        result.put("refundLogs", jdbc.queryForList(
                """
                select l.id, l.payment_id, l.payment_cancel_id, l.correlation_id, l.process_type
                from payment_process_log l join payment p on p.id = l.payment_id
                where l.payment_cancel_id is not null and (
                    p.registration_id in (select id from registration where event_id = ?)
                    or p.organization_id in (select id from organization where event_id = ?))
                order by l.id
                """, eventId, eventId));

        return result;
    }
}
