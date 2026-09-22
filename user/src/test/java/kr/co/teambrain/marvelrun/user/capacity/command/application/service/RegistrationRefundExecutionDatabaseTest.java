package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_log.PaymentProcessType;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.*;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationModificationParticipantRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.*;
import kr.co.teambrain.marvelrun.user.event.command.application.service.*;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.*;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.AdditionalPaymentTargetResolver;
import kr.co.teambrain.marvelrun.user.payment.command.application.refund.*;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.refund.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 실제 테스트 DB의 트랜잭션 경계·환불 반영·중복 실행을 검증한다. Toss는 모두 Mock이다. */
@Import({
        AdditionalPaymentTargetResolver.class,
        RegistrationModificationTransactionService.class,
        RefundExecutionLock.class, RefundExecutionTransactionService.class,
        ModificationRefundExecutor.class, ModificationRefundResultReader.class,
        RegistrationInformationPolicyValidator.class, OrgRegistrationPersonalInformationValidator.class,
        OrgRegistrationPersonalInformationService.class, OrgRegistrationModificationGuard.class,
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
        RegistrationModificationCommandService.class,
        RegistrationModificationClassifier.class,
        RegistrationPersonalInformationService.class,
        RegistrationPersonalInformationValidator.class,
        RegistrationUniqueInfoValidator.class,
        org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration.class
})

class RegistrationRefundExecutionDatabaseTest extends CapacityMvpTestSupport {
    @Autowired private RegistrationModificationCommandService commands;
    @Autowired private RegistrationModificationTransactionService preparation;
    @MockitoSpyBean private RefundExecutionTransactionService refundTransactions;
    @Autowired private ModificationRefundExecutor executor;
    @Autowired private ModificationRefundResultReader resultReader;
    @MockitoBean private TossPaymentCancelClient cancelClient;

    /** 기존 개인 수정 API 하나로 준비·외부 취소·DB 반영·최종 응답까지 이어진다. */
    @Test
    void existingModificationApiCompletesRefundWithoutChangingOriginalLedger() {
        RegistrationCreateResponse original = paidPersonal();
        mockCancelSuccess();
        RegistrationModificationSettlementResult result = commands.modifyPersonal(eventId, original.registrationId(),
                personalRequest(original.registrationId(), categoryB));
        assertThat(result.refunds()).hasSize(1);
        assertThat(result.refunds().get(0).status()).isEqualTo(PaymentCancelStatus.DONE);
        assertThat(result.members().get(0).paidAmount()).isEqualByComparingTo("30000");
        assertThat(result.members().get(0).balance()).isEqualByComparingTo("0");
        assertThat(result.members().get(0).status().name()).isEqualTo("CONFIRMED");
        assertThat(amount("select amount from payment where id = ?", original.paymentId())).isEqualByComparingTo("40000");
        assertThat(amount("select allocated_amount from payment_allocation where payment_id = ?", original.paymentId()))
                .isEqualByComparingTo("40000");
        assertThat(s("select process_status from payment where id = ?", original.paymentId())).isEqualTo("COMPLETED");
        assertThat(s("select toss_status from payment where id = ?", original.paymentId())).isEqualTo("PARTIAL_CANCELED");
        assertThat(result.orders()).isEmpty();
        verify(cancelClient, times(1)).cancel(any());
        assertThat(s("select JSON_UNQUOTE(JSON_EXTRACT(metadata,'$.resultComparison.status')) from payment_process_log where payment_cancel_id=? and process_type='CANCEL_SUCCEEDED'", result.refunds().get(0).paymentCancelId())).isEqualTo("SUCCESS");
    }

    /** 참가비가 0원으로 바뀌면 원결제를 전액 환불하고 참가 신청 자체는 확정 상태로 유지한다. */
    @Test
    void zeroPriceModificationRefundsFullPaymentAndKeepsRegistrationConfirmed() {
        RegistrationCreateResponse original = paidPersonal();
        jdbc.update("update event_category set amount = ? where id = ?", BigDecimal.ZERO, categoryB);
        mockCancelSuccess();
        RegistrationModificationSettlementResult result = commands.modifyPersonal(eventId, original.registrationId(),
                personalRequest(original.registrationId(), categoryB));
        assertThat(result.refunds().get(0).amount()).isEqualByComparingTo("40000");
        assertThat(result.refunds().get(0).status()).isEqualTo(PaymentCancelStatus.DONE);
        assertThat(result.members().get(0).status().name()).isEqualTo("CONFIRMED");
        assertThat(result.members().get(0).paidAmount()).isEqualByComparingTo("0");
        assertThat(s("select toss_status from payment where id = ?", original.paymentId())).isEqualTo("CANCELED");
        assertThat(s("select status from reservation where registration_id = ?", original.registrationId())).isEqualTo("CONSUMED");
    }

    /** 실패 또는 결과불명에서는 수정은 유지하고 실제 순납부액을 줄이지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "UNKNOWN"})
    void unsuccessfulRefundKeepsPaidAmountAndCommittedModification(String status) {
        RegistrationCreateResponse original = paidPersonal();
        TossCancelOutcome outcome = status.equals("FAILED")
                ? TossCancelOutcome.rejected(403, "EXCEED_MAX_REFUND_DUE")
                : TossCancelOutcome.unknown(null, "timeout");
        when(cancelClient.cancel(any())).thenReturn(outcome);
        RegistrationModificationSettlementResult result = commands.modifyPersonal(eventId, original.registrationId(),
                personalRequest(original.registrationId(), categoryB));
        assertThat(result.refunds().get(0).status().name()).isEqualTo(status);
        assertThat(result.members().get(0).contractAmount()).isEqualByComparingTo("30000");
        assertThat(result.members().get(0).paidAmount()).isEqualByComparingTo("40000");
        assertThat(result.members().get(0).status().name()).isEqualTo("PARTIAL_REFUND_REQUIRED");
        if (status.equals("UNKNOWN")) {
            expectError(ErrorCode.PAYMENT_CANCEL_CONFLICT, () -> commands.modifyPersonal(eventId,
                    original.registrationId(), personalRequest(original.registrationId(), categoryA)));
        }
        verify(cancelClient, times(1)).cancel(any());
        assertThat(s("select JSON_UNQUOTE(JSON_EXTRACT(metadata,'$.resultComparison.status')) from payment_process_log where payment_cancel_id=? and process_type=?", result.refunds().get(0).paymentCancelId(), status.equals("FAILED") ? "CANCEL_FAILED" : "CANCEL_UNKNOWN"))
                .isEqualTo(status.equals("FAILED") ? "FAILED" : "UNVERIFIED");
    }

    /** 같은 외부 성공 결과를 두 번 적용해도 신청 금액과 완료 로그는 한 번만 변경된다. */
    @Test
    void duplicateSuccessIsAppliedOnceAndResourcesRemainUnchanged() {
        RegistrationCreateResponse original = paidPersonal();
        RegistrationModificationSettlementResult prepared = preparation.modifyPersonal(eventId, original.registrationId(),
                personalRequest(original.registrationId(), categoryB));
        List<Map<String, Object>> resources = resourceSnapshot();
        RefundExecutionTicket ticket = refundTransactions.begin(eventId, null, prepared.refunds().get(0)).orElseThrow();
        TossCancelOutcome success = success(ticket.attempt());
        refundTransactions.apply(ticket, success);
        refundTransactions.apply(ticket, success);
        assertThat(amount("select paid_amount from registration where id = ?", original.registrationId())).isEqualByComparingTo("30000");
        assertThat(n("select count(*) from payment_process_log where payment_cancel_id = ? and process_type = 'CANCEL_SUCCEEDED'",
                ticket.attempt().paymentCancelId())).isEqualTo(1);
        assertThat(resourceSnapshot()).isEqualTo(resources);
        verifyNoInteractions(cancelClient);
    }

    /** 성공 반영 중 SQL까지 수행한 뒤 실패해도 금액·DONE은 롤백되고 UNKNOWN으로 보존된다. */
    @Test
    void databaseFailureAfterExternalSuccessRollsBackMoneyAndStoresUnknown() {
        RegistrationCreateResponse original = paidPersonal();
        mockCancelSuccess();
        RefundExecutionTransactionService target = AopTestUtils.getUltimateTargetObject(refundTransactions);
        doAnswer(invocation -> {
            invocation.callRealMethod(); // 실제 flush까지 수행한 뒤 같은 트랜잭션 안에서 실패시킨다.
            throw new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
        }).when(target).apply(any(), argThat(outcome -> outcome != null
                && outcome.kind() == TossCancelOutcome.Kind.VERIFIED));
        RegistrationModificationSettlementResult result = commands.modifyPersonal(eventId, original.registrationId(),
                personalRequest(original.registrationId(), categoryB));
        String cancelId = result.refunds().get(0).paymentCancelId();
        assertThat(result.refunds().get(0).status()).isEqualTo(PaymentCancelStatus.UNKNOWN);
        assertThat(result.members().get(0).paidAmount()).isEqualByComparingTo("40000");
        assertThat(s("select toss_status from payment where id = ?", original.paymentId())).isEqualTo("DONE");
        assertThat(n("select count(*) from payment_process_log where payment_cancel_id = ? and process_type = 'CANCEL_SUCCEEDED'", cancelId)).isZero();
        assertThat(n("select count(*) from payment_process_log where payment_cancel_id = ? and process_type = 'CANCEL_UNKNOWN' and transaction_key is not null", cancelId)).isEqualTo(1);
        verify(cancelClient, times(1)).cancel(any());
        assertThat(s("select JSON_UNQUOTE(JSON_EXTRACT(metadata,'$.resultComparison.status')) from payment_process_log where payment_cancel_id=? and process_type='CANCEL_UNKNOWN'", result.refunds().get(0).paymentCancelId())).isEqualTo("MISMATCH");
    }

    /** 외부 응답을 기다리는 동안 동일 시도가 재진입해도 두 번째 외부 호출은 발생하지 않는다. */
    @Test
    void concurrentExecutionDoesNotSendCancellationTwice() throws Exception {
        RegistrationCreateResponse original = paidPersonal();
        RegistrationModificationSettlementResult prepared = preparation.modifyPersonal(eventId, original.registrationId(),
                personalRequest(original.registrationId(), categoryB));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(cancelClient.cancel(any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            entered.countDown();
            assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
            return success(invocation.getArgument(0));
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> executor.execute(eventId, null, prepared.refunds()));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> second = pool.submit(() -> executor.execute(eventId, null, prepared.refunds()));
            second.get(5, TimeUnit.SECONDS);
            assertThat(calls.get()).isEqualTo(1);
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            assertThat(resultReader.read(prepared).refunds().get(0).status()).isEqualTo(PaymentCancelStatus.DONE);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 단체 일부 제거와 남은 구성원 가격 인하를 원결제의 각 귀속 금액대로 반영한다. */
    @Test
    void groupRefundUsesEachOriginalAllocationIncludingRemovedMember() {
        OrgRegistrationCreateResponse original = group(categoryA, categoryA);
        mockApprovalSuccess();
        payments.confirm(confirmRequest(original.paymentId()));
        jdbc.update("update event_category set amount = ? where id = ?", new BigDecimal("30000"), categoryB);
        mockCancelSuccess();
        String retained = original.registrationIds().get(0);
        String removed = original.registrationIds().get(1);
        OrgRegistrationModificationRequest request = new OrgRegistrationModificationRequest(true,
                "test@example.com",
                "테스트 주소",
                "상세",
                "테스트 단체장",
                java.time.LocalDate.of(1990, 1, 1),
                "010-0000-0000",
                new OrganizationAccessRequest(s("select login_id from organization where id = ?", original.organizationId()), "Test1234!"),
                List.of(new OrgRegistrationModificationParticipantRequest(retained, categoryB,
                        List.of(new SouvenirJson(souvenirId, "M")),
                        s("select name from registration where id = ?", retained),
                        s("select ph_num from registration where id = ?", retained),
                        s("select birth from registration where id = ?", retained), GenderClass.M)));
        RegistrationModificationSettlementResult result = commands.modifyOrganization(eventId, original.organizationId(), request);
        assertThat(result.refunds()).hasSize(1);
        assertThat(result.refunds().get(0).amount()).isEqualByComparingTo("50000");
        assertThat(result.refunds().get(0).status()).isEqualTo(PaymentCancelStatus.DONE);
        assertThat(amount("select paid_amount from registration where id = ?", retained)).isEqualByComparingTo("30000");
        assertThat(amount("select paid_amount from registration where id = ?", removed)).isEqualByComparingTo("0");
        assertThat(s("select status from registration where id = ?", removed)).isEqualTo("CANCELED");
        assertThat(amount("select amount from payment where id = ?", original.paymentId())).isEqualByComparingTo("80000");
    }

    /** 환불 결과 대기 중 변경한 개인정보를 금융 결과 반영이 덮어쓰지 않는다. */
    @Test
    void refundResultDoesNotOverwritePersonalInformationChangedAfterStart() {
        RegistrationCreateResponse original = paidPersonal();
        RegistrationModificationSettlementResult prepared = preparation.modifyPersonal(eventId, original.registrationId(),
                personalRequest(original.registrationId(), categoryB));
        RefundExecutionTicket ticket = refundTransactions.begin(eventId, null, prepared.refunds().get(0)).orElseThrow();
        tx.executeWithoutResult(status -> jdbc.update(
                "update registration set name = ?, version = version + 1 where id = ?", "환불대기중정정", original.registrationId()));
        refundTransactions.apply(ticket, success(ticket.attempt()));
        assertThat(s("select name from registration where id = ?", original.registrationId())).isEqualTo("환불대기중정정");
        assertThat(amount("select paid_amount from registration where id = ?", original.registrationId())).isEqualByComparingTo("30000");
    }

    /** 같은 원결제를 다시 부분 환불해도 과거 취소를 이번 금액에 중복 반영하지 않는다. */
    @Test
    void secondPriceReductionUsesCompletedHistoryWithoutDoubleCounting() {
        RegistrationCreateResponse original = paidPersonal();
        mockCancelSuccess();
        commands.modifyPersonal(eventId, original.registrationId(), personalRequest(original.registrationId(), categoryB));
        jdbc.update("update event_category set amount = ? where id = ?", new BigDecimal("20000"), categoryA);
        RegistrationModificationSettlementResult result = commands.modifyPersonal(eventId, original.registrationId(),
                personalRequest(original.registrationId(), categoryA));
        assertThat(result.refunds().get(0).amount()).isEqualByComparingTo("10000");
        assertThat(result.refunds().get(0).status()).isEqualTo(PaymentCancelStatus.DONE);
        assertThat(result.members().get(0).paidAmount()).isEqualByComparingTo("20000");
        assertThat(n("select count(*) from payment_cancel where payment_id = ? and status = 'DONE'", original.paymentId())).isEqualTo(2);
        assertThat(amount("select sum(cancel_amount) from payment_cancel where payment_id = ? and status = 'DONE'", original.paymentId()))
                .isEqualByComparingTo("20000");
        verify(cancelClient, times(2)).cancel(any());
    }

    /** 두 원결제 중 한 건만 성공하면 성공한 귀속만 차감하고 다른 건은 UNKNOWN으로 남긴다. */
    @Test
    void partialSuccessAcrossOriginalPaymentsPreservesUnresolvedBalance() {
        RegistrationCreateResponse original = paidPersonal();
        jdbc.update("update event_category set amount = ? where id = ?", new BigDecimal("60000"), categoryB);
        RegistrationModificationSettlementResult additional = commands.modifyPersonal(eventId, original.registrationId(),
                personalRequest(original.registrationId(), categoryB));
        String additionalId = additional.orders().get(0).paymentId();
        // 06 승인 구현과 독립적으로 07의 결과 반영을 검증하기 위한 확정 추가결제 원장 fixture이다.
        // 원 Payment/Allocation의 금액은 생성된 그대로 두고 승인 상태와 신청 순납부액만 준비한다.
        tx.executeWithoutResult(status -> {
            assertThat(jdbc.update("update payment set process_status = 'COMPLETED', toss_status = 'DONE', payment_key = ?, approved_at = ?, version = version + 1 where id = ? and process_status = 'READY'",
                    "test-additional-" + additionalId, NOW, additionalId)).isEqualTo(1);
            assertThat(jdbc.update("update registration set paid_amount = ?, status = 'CONFIRMED', version = version + 1 where id = ?",
                    new BigDecimal("60000"), original.registrationId())).isEqualTo(1);
        });
        jdbc.update("update event_category set amount = ? where id = ?", new BigDecimal("10000"), categoryA);
        AtomicInteger calls = new AtomicInteger();
        when(cancelClient.cancel(any())).thenAnswer(invocation -> calls.incrementAndGet() == 1
                ? success(invocation.getArgument(0)) : TossCancelOutcome.unknown(null, "timeout"));
        RegistrationModificationSettlementResult result = commands.modifyPersonal(eventId, original.registrationId(),
                personalRequest(original.registrationId(), categoryA));
        assertThat(result.refunds()).hasSize(2);
        assertThat(result.refunds().stream().filter(refund -> refund.status() == PaymentCancelStatus.DONE).count()).isEqualTo(1);
        assertThat(result.refunds().stream().filter(refund -> refund.status() == PaymentCancelStatus.UNKNOWN).count()).isEqualTo(1);
        BigDecimal completed = result.refunds().stream().filter(refund -> refund.status() == PaymentCancelStatus.DONE)
                .map(RegistrationModificationSettlementResult.Refund::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(result.members().get(0).paidAmount()).isEqualByComparingTo(new BigDecimal("60000").subtract(completed));
        assertThat(result.members().get(0).balance()).isNegative();
        verify(cancelClient, times(2)).cancel(any());
    }

    /** DB 기존 명단에서 제거를 판정하고 환불 한 번·통합 결제 한 번으로 최종 금액을 반영한다. */
    @Test
    void groupModificationRefundsSeparatelyAndChargesOneCombinedOrder() {
        MixedModificationFixture fixture = mixedModificationFixture(true);
        RegistrationModificationSettlementResult result = fixture.result();
        assertThat(result.orders()).hasSize(1);
        assertThat(result.orders().get(0).amount()).isEqualByComparingTo("60000");
        assertThat(result.refunds()).hasSize(1);
        assertThat(result.refunds().get(0).amount()).isEqualByComparingTo("40000");
        assertThat(result.refunds().get(0).status()).isEqualTo(PaymentCancelStatus.DONE);
        String paymentId = result.orders().get(0).paymentId();
        assertThat(s("select purpose from payment where id = ?", paymentId)).isEqualTo("MIXED_PAYMENT");
        payments.confirm(confirmRequest(paymentId));
        assertThat(amount("select paid_amount from registration where id = ?", fixture.retainedId())).isEqualByComparingTo("60000");
        assertThat(amount("select paid_amount from registration where id = ?", fixture.addedId())).isEqualByComparingTo("40000");
        assertThat(amount("select paid_amount from registration where id = ?", fixture.removedId())).isEqualByComparingTo("0");
        assertThat(s("select status from registration where id = ?", fixture.removedId())).isEqualTo("CANCELED");
        assertThat(amount("select amount from payment where id = ?", fixture.originalPaymentId())).isEqualByComparingTo("80000");
        counters(total, 0, 2);
        verify(toss, times(1)).confirm(any(), anyString());
        verify(cancelClient, times(1)).cancel(any());
    }

    /** 미승인 혼합 주문 뒤 다시 전체 수정하면 이전 주문을 무효화하고 새 결과만 승인 가능하게 한다. */
    @Test
    void fullModificationInvalidatesPreviousMixedOrder() {
        MixedModificationFixture fixture = mixedModificationFixture(true);
        String oldId = fixture.result().orders().get(0).paymentId();
        RegistrationModificationSettlementResult changed = commands.modifyOrganization(eventId, fixture.organizationId(),
                new OrgRegistrationModificationRequest(true,
                "test@example.com",
                "테스트 주소",
                "상세",
                "테스트 단체장",
                java.time.LocalDate.of(1990, 1, 1),
                "010-0000-0000",
                mixedOrganizationAccess(fixture.organizationId()),
                List.of(
                        mixedStoredParticipant(fixture.retainedId(), categoryA, "S"),
                        mixedStoredParticipant(fixture.addedId(), categoryA, "S"))));
        assertThat(s("select process_status from payment where id = ?", oldId)).isEqualTo("INVALIDATED");
        assertThat(changed.orders()).hasSize(1);
        assertThat(changed.orders().get(0).amount()).isEqualByComparingTo("40000");
        expectError(ErrorCode.PAYMENT_NOT_CONFIRMABLE, () -> payments.confirm(confirmRequest(oldId)));
        verifyNoInteractions(toss);
    }

    /** 환불 결과가 불명확하면 같은 수정에서 준비한 혼합 결제도 외부 승인 전에 차단한다. */
    @Test
    void unknownRefundBlocksCombinedPaymentAndNextModification() {
        MixedModificationFixture fixture = mixedModificationFixture(false);
        assertThat(fixture.result().refunds().get(0).status()).isEqualTo(PaymentCancelStatus.UNKNOWN);
        expectError(ErrorCode.PAYMENT_CANCEL_CONFLICT,
                () -> payments.confirm(confirmRequest(fixture.result().orders().get(0).paymentId())));
        expectError(ErrorCode.PAYMENT_CANCEL_CONFLICT,
                () -> commands.modifyOrganization(eventId, fixture.organizationId(),
                        new OrgRegistrationModificationRequest(true,
                "test@example.com",
                "테스트 주소",
                "상세",
                "테스트 단체장",
                java.time.LocalDate.of(1990, 1, 1),
                "010-0000-0000",
                mixedOrganizationAccess(fixture.organizationId()),
                List.of(
                                mixedStoredParticipant(fixture.retainedId(), categoryA, "S"),
                                mixedStoredParticipant(fixture.addedId(), categoryA, "S")))));
        verifyNoInteractions(toss);
        assertThat(amount("select paid_amount from registration where id = ?", fixture.removedId())).isEqualByComparingTo("40000");
    }

    /** 요청 명단에 다른 단체의 ID나 중복 ID를 넣어도 DB 소속을 기준으로 거절한다. */
    @Test
    void finalListCannotClaimForeignOrDuplicateRegistration() {
        OrgRegistrationCreateResponse first = group(categoryA);
        OrgRegistrationCreateResponse other = group(categoryA);
        String foreign = other.registrationIds().get(0);
        int paymentsBefore = n("select count(*) from payment where organization_id = ?", first.organizationId());
        expectError(ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET,
                () -> commands.modifyOrganization(eventId, first.organizationId(),
                        new OrgRegistrationModificationRequest(true,
                "test@example.com",
                "테스트 주소",
                "상세",
                "테스트 단체장",
                java.time.LocalDate.of(1990, 1, 1),
                "010-0000-0000",
                mixedOrganizationAccess(first.organizationId()),
                List.of(mixedStoredParticipant(foreign, categoryA, "S")))));
        String own = first.registrationIds().get(0);
        expectError(ErrorCode.DUPLICATE_REGISTRATION_MODIFICATION_TARGET,
                () -> commands.modifyOrganization(eventId, first.organizationId(),
                        new OrgRegistrationModificationRequest(true,
                "test@example.com",
                "테스트 주소",
                "상세",
                "테스트 단체장",
                java.time.LocalDate.of(1990, 1, 1),
                "010-0000-0000",
                mixedOrganizationAccess(first.organizationId()),
                List.of(mixedStoredParticipant(own, categoryA, "S"), mixedStoredParticipant(own, categoryA, "S")))));
        assertThat(n("select count(*) from payment where organization_id = ?", first.organizationId())).isEqualTo(paymentsBefore);
        verifyNoInteractions(toss, cancelClient);
    }

    /** 실제 신청·승인을 거쳐 확정된 개인 신청을 만들고 수정 대상 가격을 낮춘다. */
    private RegistrationCreateResponse paidPersonal() {
        RegistrationCreateResponse original = personal(categoryA, "S", "1990-01-01");
        mockApprovalSuccess();
        payments.confirm(confirmRequest(original.paymentId()));
        jdbc.update("update event_category set amount = ? where id = ?", new BigDecimal("30000"), categoryB);
        return original;
    }

    /** 본인확인 정보는 현재 DB 값으로 구성하고 종목·기념품 변경 요청을 만든다. */
    private RegistrationModificationRequest personalRequest(String id, String category) {
        String name = s("select name from registration where id = ?", id);
        String phone = s("select ph_num from registration where id = ?", id);
        String birth = s("select birth from registration where id = ?", id);
        return new RegistrationModificationRequest(new RegistrationAccessRequest(name, birth, phone, "Test1234!"),
                category,
                List.of(new SouvenirJson(souvenirId, "M")),
                name,
                phone,
                birth,
                GenderClass.M,
                "수정 주소",
                "수정 상세",
                true,
                "테스트 보호자",
                null,
                null,
                null);
    }

    /** HTTP를 대신하면서 시작 기록이 먼저 커밋됐고 활성 트랜잭션이 없는지 확인한다. */
    private void mockCancelSuccess() {
        when(cancelClient.cancel(any())).thenAnswer(invocation -> {
            TossCancelAttempt attempt = invocation.getArgument(0);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(n("select count(*) from payment_cancel where id = ? and requested_at is not null and status = 'PROCESSING'",
                    attempt.paymentCancelId())).isEqualTo(1);
            assertThat(s("select idempotency_key from payment_cancel where id = ?", attempt.paymentCancelId()))
                    .isEqualTo(attempt.idempotencyKey());
            return success(attempt);
        });
    }

    /** 통신 검증을 통과한 금액·잔액·거래키의 성공 증거를 생성한다. */
    private static TossCancelOutcome success(TossCancelAttempt attempt) {
        BigDecimal remaining = attempt.originalAmount().subtract(attempt.cancelAmount());
        for (BigDecimal amount : attempt.completedCancels().values()) { remaining = remaining.subtract(amount); }
        return TossCancelOutcome.verified(new VerifiedTossCancellation("tx-" + attempt.paymentCancelId(),
                attempt.cancelAmount(), remaining, OffsetDateTime.parse("2026-09-20T12:00:00+09:00"),
                remaining.signum() == 0 ? "CANCELED" : "PARTIAL_CANCELED"));
    }

    /** 환불 실행 자체가 예약·정원을 다시 변경하지 않는지 비교할 스냅샷이다. */
    private List<Map<String, Object>> resourceSnapshot() {
        List<Map<String, Object>> rows = new ArrayList<>(jdbc.queryForList(
                "select id, held_count, confirmed_count, updated_at from capacity where event_id = ? order by id", eventId));
        rows.addAll(jdbc.queryForList("select rv.id, rv.status, rv.version from reservation rv join registration r on r.id = rv.registration_id where r.event_id = ? order by rv.id", eventId));
        return rows;
    }

    /** DB 금액은 부동소수점 변환 없이 조회한다. */
    private BigDecimal amount(String sql, Object... values) { return jdbc.queryForObject(sql, BigDecimal.class, values); }

    /** 기존 두 명 중 한 명을 제거하고 한 명의 추가금 및 신규 한 명의 참가비를 동시에 준비한다. */
    private MixedModificationFixture mixedModificationFixture(boolean refundSucceeds) {
        OrgRegistrationCreateResponse original = group(categoryA, categoryA);
        mockApprovalSuccess();
        payments.confirm(confirmRequest(original.paymentId()));
        clearInvocations(toss);
        jdbc.update("update event_category set amount = 60000 where id = ?", categoryB);
        if (refundSucceeds) {
            mockCancelSuccess();
        } else {
            when(cancelClient.cancel(any())).thenReturn(TossCancelOutcome.unknown(null, "테스트 응답 유실"));
        }
        String retained = original.registrationIds().get(0);
        String removed = original.registrationIds().get(1);
        OrgRegistrationModificationRequest request = new OrgRegistrationModificationRequest(true,
                "test@example.com",
                "테스트 주소",
                "상세",
                "테스트 단체장",
                java.time.LocalDate.of(1990, 1, 1),
                "010-0000-0000",
                mixedOrganizationAccess(original.organizationId()),
                List.of(
                mixedStoredParticipant(retained, categoryB, "M"),
                new OrgRegistrationModificationParticipantRequest(null, categoryA,
                        List.of(new SouvenirJson(souvenirId, "S")), "신규" + UUID.randomUUID().toString().substring(0, 8),
                        "010-1111-2222", "1990-01-01", GenderClass.M)));
        RegistrationModificationSettlementResult result = commands.modifyOrganization(eventId, original.organizationId(), request);
        String added = result.members().stream().map(RegistrationModificationSettlementResult.Member::registrationId)
                .filter(id -> !original.registrationIds().contains(id)).findFirst().orElseThrow();
        return new MixedModificationFixture(original.organizationId(), original.paymentId(), retained, removed, added, result);
    }

    /** 현재 DB 로그인 정보로 단체 접근 요청을 구성한다. */
    private OrganizationAccessRequest mixedOrganizationAccess(String organizationId) {
        return new OrganizationAccessRequest(s("select login_id from organization where id = ?", organizationId), "Test1234!");
    }

    /** 기존 참가자 정보는 DB에서 가져오며 요청에는 수정 후 종목과 기념품을 담는다. */
    private OrgRegistrationModificationParticipantRequest mixedStoredParticipant(String id, String category, String size) {
        return new OrgRegistrationModificationParticipantRequest(id, category, List.of(new SouvenirJson(souvenirId, size)),
                s("select name from registration where id = ?", id), s("select ph_num from registration where id = ?", id),
                s("select birth from registration where id = ?", id), GenderClass.M);
    }

    /** 혼합 수정 시나리오의 식별자와 최초 수정 결과를 보관한다. */
    private record MixedModificationFixture(String organizationId, String originalPaymentId,
                                            String retainedId, String removedId, String addedId, RegistrationModificationSettlementResult result) { }
}