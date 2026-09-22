package kr.co.teambrain.marvelrun.admin.payment.command.application.refund;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.admin.payment.command.application.refund.AdminRefundExecutionRequest.Refund;
import kr.co.teambrain.marvelrun.admin.payment.command.infrastructure.toss.refund.*;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 외부 요청은 한 번만 실행하고 결과 저장 실패를 결과불명 처리로 연결하는지 검증한다. */
class ModificationRefundExecutorTest {
    private final RefundExecutionTransactionService transactions = mock(RefundExecutionTransactionService.class);
    private final TossPaymentCancelClient toss = mock(TossPaymentCancelClient.class);
    private final ModificationRefundExecutor executor = new ModificationRefundExecutor(transactions, toss);

    /** 이미 시작한 취소는 다시 Toss에 전송하지 않는다. */
    @Test
    void skipsAlreadyClaimedAttempt() {
        Refund refund = refund("cancel");
        when(transactions.begin("event", null, refund)).thenReturn(Optional.empty());
        List<AdminRefundExecutionResult> results = executor.execute("event", null, List.of(refund));
        assertThat(results.getFirst().started()).isFalse();
        verifyNoInteractions(toss);
        verify(transactions, never()).apply(any(), any());
    }

    /** 성공 저장 실패 시 외부 성공 증거를 유지하여 UNKNOWN 저장을 요청한다. */
    @Test
    void keepsSuccessEvidenceWhenDatabaseApplyFails() {
        Refund refund = refund("cancel");
        RefundExecutionTicket ticket = ticket("cancel");
        TossCancelOutcome success = success();
        when(transactions.begin("event", null, refund)).thenReturn(Optional.of(ticket));
        when(toss.cancel(ticket.attempt())).thenReturn(success);
        doThrow(new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR))
                .when(transactions).apply(ticket, success);
        List<AdminRefundExecutionResult> results = executor.execute("event", null, List.of(refund));
        assertThat(results.getFirst().unknownStored()).isTrue();
        assertThat(results.getFirst().outcomeStored()).isFalse();
        assertThat(results.getFirst().externalOutcome()).isEqualTo(success);
        ArgumentCaptor<TossCancelOutcome> outcomes = ArgumentCaptor.forClass(TossCancelOutcome.class);
        verify(transactions, times(2)).apply(eq(ticket), outcomes.capture());
        TossCancelOutcome fallback = outcomes.getAllValues().get(1);
        assertThat(fallback.kind()).isEqualTo(TossCancelOutcome.Kind.UNKNOWN);
        assertThat(fallback.cancellation()).isEqualTo(success.cancellation());
        verify(toss, times(1)).cancel(ticket.attempt());
    }

    /** 한 원결제의 결과불명이 같은 요청의 다른 독립 원결제 처리까지 롤백하지 않는다. */
    @Test
    void continuesOtherPreparedPaymentWithoutRetryingFirst() {
        Refund first = refund("first");
        Refund second = refund("second");
        RefundExecutionTicket one = ticket("first");
        RefundExecutionTicket two = ticket("second");
        TossCancelOutcome unknown = TossCancelOutcome.unknown(null, "timeout");
        TossCancelOutcome success = success();
        when(transactions.begin("event", null, first)).thenReturn(Optional.of(one));
        when(transactions.begin("event", null, second)).thenReturn(Optional.of(two));
        when(toss.cancel(one.attempt())).thenReturn(unknown);
        when(toss.cancel(two.attempt())).thenReturn(success);
        executor.execute("event", null, List.of(first, second));
        verify(transactions).apply(one, unknown);
        verify(transactions).apply(two, success);
        verify(toss, times(1)).cancel(one.attempt());
        verify(toss, times(1)).cancel(two.attempt());
    }

    /** 시작 기록을 커밋했는지 확인할 수 없으면 외부 환불을 보내지 않는다. */
    @Test
    void doesNotCallTossWhenStartCommitFails() {
        Refund refund = refund("cancel");
        when(transactions.begin("event", null, refund))
                .thenThrow(new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR));
        List<AdminRefundExecutionResult> results = executor.execute("event", null, List.of(refund));
        assertThat(results.getFirst().errorCode()).isEqualTo("CANCEL_BEGIN_FAILED");
        verifyNoInteractions(toss);
    }

    /** 배치 전체에 열린 트랜잭션이 있으면 시작 기록과 외부 전송 전에 차단한다. */
    @Test
    void rejectsAmbientTransaction() {
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> executor.execute("event", null, List.of(refund("cancel"))))
                    .isInstanceOfSatisfying(CustomException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_CANCEL_CONFLICT));
            verifyNoInteractions(transactions, toss);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    /** 실제 DB 값 대신 고정 환불 식별자를 구성한다. */
    private static Refund refund(String id) {
        return new Refund(id, "payment-" + id, new BigDecimal("10000"), PaymentCancelStatus.PROCESSING, "correlation");
    }

    /** 중첩 Mock 설정 없이 실행 정보를 먼저 만든다. */
    private static RefundExecutionTicket ticket(String id) {
        TossCancelAttempt attempt = new TossCancelAttempt(id, "key", "order", "idem-" + id,
                "신청 수정", new BigDecimal("40000"), new BigDecimal("10000"), Map.of());
        return new RefundExecutionTicket("event", null, "payment-" + id, "correlation", attempt, List.of());
    }

    /** 검증된 성공 증거를 구성한다. */
    private static TossCancelOutcome success() {
        return TossCancelOutcome.verified(new VerifiedTossCancellation("transaction", new BigDecimal("10000"),
                new BigDecimal("30000"), OffsetDateTime.parse("2026-09-20T12:00:00+09:00"), "PARTIAL_CANCELED"));
    }
}