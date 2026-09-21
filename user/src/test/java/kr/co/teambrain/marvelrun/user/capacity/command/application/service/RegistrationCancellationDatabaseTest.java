package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.OrgRegistrationCreateResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.RegistrationCreateResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationCancellationCommandService;
import kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationCancellationTransactionService;
import kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationModificationPaymentGuard;
import kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationModificationSettlementService;
import kr.co.teambrain.marvelrun.user.payment.command.application.ModificationRefundPreparationService;
import kr.co.teambrain.marvelrun.user.payment.command.application.refund.ModificationRefundExecutor;
import kr.co.teambrain.marvelrun.user.payment.command.application.refund.ModificationRefundResultReader;
import kr.co.teambrain.marvelrun.user.payment.command.application.refund.RefundExecutionLock;
import kr.co.teambrain.marvelrun.user.payment.command.application.refund.RefundExecutionTransactionService;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.refund.TossCancelAttempt;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.refund.TossCancelOutcome;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.refund.TossPaymentCancelClient;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.refund.VerifiedTossCancellation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 실제 MySQL에서 취소 원자성·귀속 환불·중복·충돌을 검증한다. Toss 승인과 환불은 대역이다. */
@Import({RegistrationCancellationCommandService.class, RegistrationCancellationTransactionService.class,
        RegistrationModificationPaymentGuard.class, RegistrationModificationSettlementService.class,
        ReservationRemovalService.class, RefundExecutionLock.class, RefundExecutionTransactionService.class,
        ModificationRefundExecutor.class, ModificationRefundResultReader.class})
class RegistrationCancellationDatabaseTest extends CapacityMvpTestSupport {
    @Autowired private RegistrationCancellationCommandService commands;
    @Autowired private RegistrationCancellationTransactionService preparation;
    @Autowired private ModificationRefundExecutor refundExecutor;
    @Autowired private RegistrationModificationSettlementService settlement;
    @MockitoBean private TossPaymentCancelClient cancelClient;
    @MockitoSpyBean private ModificationRefundPreparationService refundPreparation;

    /** 개인·단체의 미납 신청은 주문을 무효화하고 정원만 반환하며 PG 환불을 호출하지 않는다. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unpaidCancellationReleasesExactlyOnce(boolean group) {
        Target target = target(group, false);
        RegistrationModificationSettlementResult first = cancel(target);
        RegistrationModificationSettlementResult repeated = cancel(target);
        assertThat(first.members()).hasSize(target.ids().size());
        assertThat(first.refunds()).isEmpty();
        assertThat(first.orders()).isEmpty();
        assertThat(repeated.members()).hasSize(first.members().size());

        for (int index = 0; index < first.members().size(); index++) {
            assertThat(repeated.members().get(index).registrationId())
                    .isEqualTo(first.members().get(index).registrationId());
            assertThat(repeated.members().get(index).status())
                    .isEqualTo(first.members().get(index).status());
            assertThat(repeated.members().get(index).contractAmount())
                    .isEqualByComparingTo(first.members().get(index).contractAmount());
            assertThat(repeated.members().get(index).paidAmount())
                    .isEqualByComparingTo(first.members().get(index).paidAmount());
            assertThat(repeated.members().get(index).balance())
                    .isEqualByComparingTo(first.members().get(index).balance());
        }
        for (String id : target.ids()) { assertCanceled(id); }
        counters(total, 0, 0);
        counters(shirtS, 0, 0);
        assertThat(s("select process_status from payment where id = ?", target.paymentId())).isEqualTo("INVALIDATED");
        verifyNoInteractions(cancelClient, toss);
    }

    /** 한 단체 원결제는 구성원 수와 무관하게 한 번 환불하고 원결제·원귀속 금액을 보존한다. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void paidCancellationRefundsOriginalOnceAndPreservesLedger(boolean group) {
        Target target = target(group, true);
        BigDecimal originalAmount = amount("select amount from payment where id = ?", target.paymentId());
        mockCancelSuccess();
        RegistrationModificationSettlementResult result = cancel(target);
        assertThat(result.refunds()).hasSize(1);
        assertThat(result.refunds().get(0).status()).isEqualTo(PaymentCancelStatus.DONE);
        assertThat(s("select purpose from payment_cancel where id=?", result.refunds().get(0).paymentCancelId()))
                .isEqualTo("REGISTRATION_CANCELLATION");
        assertThat(result.refunds().get(0).amount()).isEqualByComparingTo(originalAmount);
        assertThat(result.orders()).isEmpty();
        for (String id : target.ids()) { assertCanceled(id); }
        counters(total, 0, 0);
        assertThat(amount("select amount from payment where id = ?", target.paymentId())).isEqualByComparingTo(originalAmount);
        assertThat(amount("select sum(allocated_amount) from payment_allocation where payment_id = ?", target.paymentId()))
                .isEqualByComparingTo(originalAmount);
        assertThat(n("select count(*) from payment_cancel_allocation a join payment_cancel c on c.id=a.payment_cancel_id where c.payment_id=?",
                target.paymentId())).isEqualTo(target.ids().size());
        List<Map<String, Object>> resources = resourceSnapshot();
        // 완료된 취소 재조회는 접수 마감 이후에도 허용하되 PG를 다시 부르지 않는다.
        when(time.currentDateTime()).thenReturn(NOW.plusDays(3));
        RegistrationModificationSettlementResult repeated = cancel(target);
        assertThat(repeated.refunds()).hasSize(1);
        assertThat(repeated.refunds().get(0).status()).isEqualTo(PaymentCancelStatus.DONE);
        assertThat(resourceSnapshot()).isEqualTo(resources);
        verify(cancelClient, times(1)).cancel(any());
    }

    /** 환불 실패·결과불명은 순납부액을 보존하고 반복 취소를 새 환불 시도로 바꾸지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "UNKNOWN"})
    void unsuccessfulRefundRemainsPendingWithoutAutomaticRetry(String state) {
        Target target = target(false, true);
        TossCancelOutcome outcome = state.equals("FAILED")
                ? TossCancelOutcome.rejected(403, "EXCEED_MAX_REFUND_DUE")
                : TossCancelOutcome.unknown(null, "timeout");
        when(cancelClient.cancel(any())).thenReturn(outcome);
        RegistrationModificationSettlementResult first = cancel(target);
        RegistrationModificationSettlementResult repeated = cancel(target);
        assertThat(first.members().get(0).status()).isEqualTo(RegistrationStatus.CANCELLATION_PENDING);
        assertThat(first.members().get(0).contractAmount()).isEqualByComparingTo("0");
        assertThat(first.members().get(0).paidAmount()).isEqualByComparingTo("40000");
        assertThat(repeated.refunds().get(0).status().name()).isEqualTo(state);
        assertThat(s("select status from reservation where registration_id=?", target.ids().get(0))).isEqualTo("RELEASED");
        counters(total, 0, 0);
        assertThat(n("select count(*) from payment_cancel where payment_id=?", target.paymentId())).isEqualTo(1);
        verify(cancelClient, times(1)).cancel(any());
    }

    /** 최초·추가 두 원결제가 있으면 각 원결제의 잔여 귀속만 환불한다. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void multipleOriginalPaymentsKeepPartialSuccess(boolean failSecond) {
        Target target = target(false, true);
        String id = target.ids().get(0);
        String additionalId = tx.execute(status -> {
            jdbc.update("update registration set contract_amount=60000, status='ADDITIONAL_PAYMENT_REQUIRED', version=version+1 where id=?", id);
            em.clear();
            return settlement.settle(eventId, null, List.of(id), NOW).orders().get(0).paymentId();
        });
        payments.confirm(confirmRequest(additionalId));
        AtomicInteger calls = new AtomicInteger();
        when(cancelClient.cancel(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return failSecond && calls.incrementAndGet() == 2
                    ? TossCancelOutcome.unknown(null, "timeout") : success(invocation.getArgument(0));
        });
        RegistrationModificationSettlementResult result = cancel(target);
        assertThat(result.refunds()).hasSize(2);
        BigDecimal completed = result.refunds().stream().filter(r -> r.status() == PaymentCancelStatus.DONE)
                .map(RegistrationModificationSettlementResult.Refund::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(amount("select paid_amount from registration where id=?", id))
                .isEqualByComparingTo(new BigDecimal("60000").subtract(completed));
        assertThat(result.members().get(0).status()).isEqualTo(failSecond
                ? RegistrationStatus.CANCELLATION_PENDING : RegistrationStatus.CANCELED);
        assertThat(result.refunds().stream().filter(r -> r.status() == PaymentCancelStatus.UNKNOWN).count())
                .isEqualTo(failSecond ? 1L : 0L);
        cancel(target);
        verify(cancelClient, times(2)).cancel(any());
        counters(total, 0, 0);
    }

    /** 승인 시작 뒤에는 취소가 승인 중 예약과 주문을 변경하지 못한다. */
    @ParameterizedTest
    @ValueSource(strings = {"CONFIRMING", "UNKNOWN"})
    void unresolvedApprovalBlocksCancellation(String state) {
        Target target = target(false, false);
        paymentTransactions.beginConfirm(confirmRequest(target.paymentId()), "cancel-race", NOW);
        if (state.equals("UNKNOWN")) {
            jdbc.update("update payment set process_status='UNKNOWN', version=version+1 where id=?", target.paymentId());
        }
        List<Map<String, Object>> before = resourceSnapshot();
        expectError(ErrorCode.REGISTRATION_MODIFICATION_PAYMENT_CONFLICT, () -> cancel(target));
        assertThat(n("select is_del from registration where id=?", target.ids().get(0))).isEqualTo(0);
        assertThat(resourceSnapshot()).isEqualTo(before);
        verifyNoInteractions(cancelClient, toss);
    }

    /** 준비된 기존 환불이 미확정이면 활성 단체 구성원의 새로운 전체 취소도 차단한다. */
    @Test
    void unresolvedPreviousRefundBlocksGroupCancellation() {
        Target target = target(true, true);
        tx.executeWithoutResult(status -> {
            jdbc.update("update registration set contract_amount=30000, status='PARTIAL_REFUND_REQUIRED', version=version+1 where id=?",
                    target.ids().get(0));
            em.clear();
            settlement.settle(eventId, target.organizationId(), target.ids(), NOW);
        });
        List<Map<String, Object>> before = resourceSnapshot();
        expectError(ErrorCode.PAYMENT_CANCEL_CONFLICT, () -> cancel(target));
        assertThat(n("select count(*) from registration where organization_id=? and is_del=0", target.organizationId())).isEqualTo(2);
        assertThat(resourceSnapshot()).isEqualTo(before);
        verifyNoInteractions(cancelClient);
    }

    /** 자원 반환과 환불 원장 저장 뒤 실패해도 신청·정원·환불 준비가 함께 롤백된다. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failureAfterRefundPreparationRollsBackWholeCancellation(boolean paid) {
        Target target = target(true, paid);
        List<Map<String, Object>> before = resourceSnapshot();
        ModificationRefundPreparationService actual = AopTestUtils.getUltimateTargetObject(refundPreparation);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            em.flush();
            throw new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
        }).when(actual).prepare(anyString(), anyString(), anyList());
        expectError(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR, () -> cancel(target));
        assertThat(resourceSnapshot()).isEqualTo(before);
        assertThat(n("select count(*) from registration where organization_id=? and is_del=0", target.organizationId())).isEqualTo(2);
        assertThat(n("select count(*) from payment_cancel where payment_id=?", target.paymentId())).isZero();
        assertThat(s("select process_status from payment where id=?", target.paymentId())).isEqualTo(paid ? "COMPLETED" : "READY");
        for (String id : target.ids()) {
            assertThat(amount("select contract_amount from registration where id=?", id)).isEqualByComparingTo("40000");
        }
        verifyNoInteractions(cancelClient);
    }

    /** 무료 확정 신청은 확정 정원을 반환하지만 환불 주문을 만들지 않는다. */
    @Test
    void freeConfirmedRegistrationReleasesWithoutRefund() {
        Target target = target(false, false);
        String id = target.ids().get(0);
        tx.executeWithoutResult(status -> {
            jdbc.update("update payment set process_status='INVALIDATED', version=version+1 where id=?", target.paymentId());
            jdbc.update("update registration set contract_amount=0, version=version+1 where id=?", id);
            em.clear();
            settlement.settle(eventId, null, List.of(id), NOW);
        });
        assertThat(s("select status from reservation where registration_id=?", id)).isEqualTo("CONSUMED");
        assertThat(cancel(target).refunds()).isEmpty();
        assertCanceled(id);
        counters(total, 0, 0);
        verifyNoInteractions(cancelClient, toss);
    }

    /** 이미 반환된 미납 예약은 취소할 때 카운터를 다시 차감하지 않는다. */
    @Test
    void releasedUnpaidReservationIsNotReleasedTwice() {
        Target target = target(false, false);
        registrations.releaseReservation(eventId, target.ids().get(0));
        List<Map<String, Object>> before = resourceSnapshot();
        cancel(target);
        assertCanceled(target.ids().get(0));
        assertThat(resourceSnapshot()).isEqualTo(before);
        verifyNoInteractions(cancelClient, toss);
    }

    /** 잘못된 본인확인·대회·개인/단체 경로로 다른 참가자의 자원을 취소할 수 없다. */
    @Test
    void wrongAccessAndScopeLeaveAllTargetsUnchanged() {
        Target personal = target(false, false);
        Target group = target(true, false);
        List<Map<String, Object>> before = resourceSnapshot();
        expectError(ErrorCode.REGISTRATION_ACCESS_DENIED, () -> commands.cancelPersonal(eventId,
                personal.ids().get(0), new RegistrationAccessRequest("다른사람", "1990-01-01", "010-0000-0000", "Test1234!")));
        expectError(ErrorCode.ORGANIZATION_ACCESS_DENIED, () -> commands.cancelOrganization(eventId,
                group.organizationId(), new OrganizationAccessRequest("다른단체", "Test1234!")));
        expectError(ErrorCode.REGISTRATION_ACCESS_DENIED, () -> commands.cancelPersonal(eventId,
                group.ids().get(0), personalAccess(group.ids().get(0))));
        expectError(ErrorCode.EVENT_NOT_FOUND, () -> commands.cancelPersonal("missing-event", personal.ids().get(0),
                personalAccess(personal.ids().get(0))));
        assertThat(resourceSnapshot()).isEqualTo(before);
        assertThat(n("select count(*) from registration where event_id=? and is_del=0", eventId)).isEqualTo(3);
        verifyNoInteractions(cancelClient, toss);
    }

    /** 정원 마감 상태에서는 기간 내 취소를 허용하지만 접수 마감 정각부터 새 취소를 차단한다. */
    @Test
    void closedCapacityAllowsCancellationButDeadlineDoesNot() {
        Target first = target(false, false);
        Target second = target(false, false);
        jdbc.update("update event set event_status='CLOSED' where id=?", eventId);
        cancel(first);
        when(time.currentDateTime()).thenReturn(NOW.plusDays(1));
        expectError(ErrorCode.EVENT_REGISTRATION_CLOSED, () -> cancel(second));
        assertThat(n("select is_del from registration where id=?", second.ids().get(0))).isZero();
        cancel(first);
        counters(total, 1, 0);
        verifyNoInteractions(cancelClient, toss);
    }

    /** 동시에 같은 취소를 요청해도 원결제 환불과 자원 반환은 한 번만 발생한다. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentCancellationCreatesOneRefund(boolean group) throws Exception {
        Target target = target(group, true);
        // 본인확인용 DB 조회를 worker 밖에서 끝내 커넥션 풀을 불필요하게 점유하지 않는다.
        RegistrationAccessRequest personalAccess = group ? null : personalAccess(target.ids().get(0));
        OrganizationAccessRequest organizationAccess = group ? organizationAccess(target.organizationId()) : null;
        mockCancelSuccess();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<RegistrationModificationSettlementResult> action = () -> {
                if (!start.await(10, TimeUnit.SECONDS)) { throw new AssertionError("시작 신호 대기 초과"); }
                return group ? commands.cancelOrganization(eventId, target.organizationId(), organizationAccess)
                        : commands.cancelPersonal(eventId, target.ids().get(0), personalAccess);
            };
            Future<RegistrationModificationSettlementResult> first = workers.submit(action);
            Future<RegistrationModificationSettlementResult> second = workers.submit(action);
            start.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
        for (String id : target.ids()) { assertCanceled(id); }
        assertThat(n("select count(*) from payment_cancel where payment_id=?", target.paymentId())).isEqualTo(1);
        counters(total, 0, 0);
        verify(cancelClient, times(1)).cancel(any());
    }


    /** 기존 확정자와 신규 미납자가 섞인 단체의 혼합 주문도 전체 취소 시 목적별로 안전하게 정리한다. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void groupCancellationHandlesMixedOrderBeforeAndAfterApproval(boolean approveMixed) {
        OrgRegistrationCreateResponse original = group(categoryA);
        mockApprovalSuccess();
        payments.confirm(confirmRequest(original.paymentId()));
        RegistrationCreateResponse added = personal(categoryA, "S", "1991-01-01");
        String existingId = original.registrationIds().get(0);
        // 수정 서비스 자체는 기존 회귀 테스트가 담당한다. 여기서는 이미 준비된 혼합 주문의 취소를 검증한다.
        String mixedId = tx.execute(status -> {
            jdbc.update("update registration set contract_amount=60000, status='ADDITIONAL_PAYMENT_REQUIRED', version=version+1 where id=?", existingId);
            jdbc.update("update registration set organization_id=?, version=version+1 where id=?", original.organizationId(), added.registrationId());
            jdbc.update("update payment set process_status='INVALIDATED', version=version+1 where id=?", added.paymentId());
            em.clear();
            return settlement.settle(eventId, original.organizationId(), List.of(existingId, added.registrationId()), NOW)
                    .orders().get(0).paymentId();
        });
        if (approveMixed) { payments.confirm(confirmRequest(mixedId)); }
        mockCancelSuccess();
        RegistrationModificationSettlementResult result = commands.cancelOrganization(eventId, original.organizationId(),
                organizationAccess(original.organizationId()));
        assertThat(result.members()).hasSize(2);
        assertThat(result.refunds()).hasSize(approveMixed ? 2 : 1);
        assertThat(result.refunds().stream().map(RegistrationModificationSettlementResult.Refund::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(approveMixed ? "100000" : "40000");
        assertThat(s("select process_status from payment where id=?", mixedId)).isEqualTo(approveMixed ? "COMPLETED" : "INVALIDATED");
        assertCanceled(existingId);
        assertCanceled(added.registrationId());
        counters(total, 0, 0);
        verify(cancelClient, times(approveMixed ? 2 : 1)).cancel(any());
    }

    /** DB 취소가 먼저 커밋된 뒤 반복 요청이 와도 미실행 환불을 임의 재전송하지 않는다. */
    @Test
    void replayOfCommittedPreparationReturnsProcessingWithoutSendingRefund() {
        Target target = target(false, true);
        RegistrationCancellationTransactionService.Prepared prepared = preparation.cancelPersonal(eventId,
                target.ids().get(0), personalAccess(target.ids().get(0)));
        assertThat(prepared.executeRefunds()).isTrue();
        RegistrationModificationSettlementResult replay = cancel(target);
        assertThat(replay.members().get(0).status()).isEqualTo(RegistrationStatus.CANCELLATION_PENDING);
        assertThat(replay.refunds().get(0).status()).isEqualTo(PaymentCancelStatus.PROCESSING);
        assertThat(n("select count(*) from payment_cancel where payment_id=?", target.paymentId())).isEqualTo(1);
        counters(total, 0, 0);
        verifyNoInteractions(cancelClient);
    }


    /** 과거 부분환불을 마친 개인 취소는 원결제 전체가 아니라 남은 순납부액만 추가 환불한다. */
    @Test
    void previousPartialRefundIsNotRefundedAgain() {
        Target target = target(false, true);
        String id = target.ids().get(0);
        mockCancelSuccess();
        RegistrationModificationSettlementResult partial = tx.execute(status -> {
            jdbc.update("update registration set contract_amount=30000, status='PARTIAL_REFUND_REQUIRED', version=version+1 where id=?", id);
            em.clear();
            return settlement.settle(eventId, null, List.of(id), NOW);
        });
        refundExecutor.execute(eventId, null, partial.refunds());
        assertThat(amount("select paid_amount from registration where id=?", id)).isEqualByComparingTo("30000");
        RegistrationModificationSettlementResult result = cancel(target);
        assertThat(result.refunds()).hasSize(2);
        BigDecimal newRefund = result.refunds().stream()
                .filter(r -> !r.paymentCancelId().equals(partial.refunds().get(0).paymentCancelId()))
                .map(RegistrationModificationSettlementResult.Refund::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(newRefund).isEqualByComparingTo("30000");
        assertThat(amount("select sum(cancel_amount) from payment_cancel where payment_id=? and status='DONE'", target.paymentId()))
                .isEqualByComparingTo("40000");
        assertCanceled(id);
        verify(cancelClient, times(2)).cancel(any());
    }

    /** 개인 또는 단체 신청을 실제 생성하고 선택적으로 실제 승인 서비스까지 실행한다. */
    private Target target(boolean group, boolean paid) {
        Target target;
        if (group) {
            OrgRegistrationCreateResponse created = group(categoryA, categoryA);
            target = new Target(created.organizationId(), created.registrationIds(), created.paymentId());
        } else {
            RegistrationCreateResponse created = personal(categoryA, "S", "1990-01-01");
            target = new Target(null, List.of(created.registrationId()), created.paymentId());
        }
        if (paid) { mockApprovalSuccess(); payments.confirm(confirmRequest(target.paymentId())); }
        return target;
    }

    /** 현재 본인확인 정보만 전달하며 취소할 명단이나 금액은 전달하지 않는다. */
    private RegistrationModificationSettlementResult cancel(Target target) {
        return target.organizationId() == null
                ? commands.cancelPersonal(eventId, target.ids().get(0), personalAccess(target.ids().get(0)))
                : commands.cancelOrganization(eventId, target.organizationId(), organizationAccess(target.organizationId()));
    }

    /** 개인의 현재 DB 본인확인 정보를 만든다. */
    private RegistrationAccessRequest personalAccess(String id) {
        return new RegistrationAccessRequest(s("select name from registration where id=?", id),
                s("select birth from registration where id=?", id), s("select ph_num from registration where id=?", id), "Test1234!");
    }

    /** 단체의 현재 DB 본인확인 정보를 만든다. */
    private OrganizationAccessRequest organizationAccess(String id) {
        return new OrganizationAccessRequest(s("select login_id from organization where id=?", id), "Test1234!");
    }

    /** 원결제 이력을 고려한 검증된 성공 응답을 구성하고 외부 호출의 트랜잭션 분리를 확인한다. */
    private void mockCancelSuccess() {
        when(cancelClient.cancel(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            TossCancelAttempt attempt = invocation.getArgument(0);
            assertThat(n("select count(*) from payment_cancel where id=? and requested_at is not null and status='PROCESSING'",
                    attempt.paymentCancelId())).isEqualTo(1);
            return success(attempt);
        });
    }

    /** 실제 PG 호출 없이 환불액·잔액·거래키의 성공 증거를 만든다. */
    private static TossCancelOutcome success(TossCancelAttempt attempt) {
        BigDecimal remaining = attempt.originalAmount().subtract(attempt.cancelAmount());
        for (BigDecimal amount : attempt.completedCancels().values()) { remaining = remaining.subtract(amount); }
        return TossCancelOutcome.verified(new VerifiedTossCancellation("cancel-" + attempt.paymentCancelId(),
                attempt.cancelAmount(), remaining, OffsetDateTime.parse("2026-09-20T12:00:00+09:00"),
                remaining.signum() == 0 ? "CANCELED" : "PARTIAL_CANCELED"));
    }

    /** 취소 완료는 삭제 표기·계약금·순납부액·예약이 모두 일치해야 한다. */
    private void assertCanceled(String id) {
        assertThat(n("select is_del from registration where id=?", id)).isEqualTo(1);
        assertThat(s("select status from registration where id=?", id)).isEqualTo("CANCELED");
        assertThat(amount("select contract_amount from registration where id=?", id)).isZero();
        assertThat(amount("select paid_amount from registration where id=?", id)).isZero();
        assertThat(s("select status from reservation where registration_id=?", id)).isEqualTo("RELEASED");
    }

    /** 예약 version과 정원 카운터로 반환 중복·실패 롤백을 비교한다. */
    private List<Map<String, Object>> resourceSnapshot() {
        java.util.ArrayList<Map<String, Object>> rows = new java.util.ArrayList<>(jdbc.queryForList(
                "select id, held_count, confirmed_count, updated_at from capacity where event_id=? order by id", eventId));
        rows.addAll(jdbc.queryForList("select rv.id, rv.status, rv.version from reservation rv join registration r on r.id=rv.registration_id where r.event_id=? order by rv.id", eventId));
        return rows;
    }

    /** 금융 금액은 정수나 부동소수점으로 바꾸지 않고 조회한다. */
    private BigDecimal amount(String sql, Object... args) { return jdbc.queryForObject(sql, BigDecimal.class, args); }

    /** 테스트가 생성한 신청 범위와 원주문을 보관한다. */
    private record Target(String organizationId, List<String> ids, String paymentId) { }
}
