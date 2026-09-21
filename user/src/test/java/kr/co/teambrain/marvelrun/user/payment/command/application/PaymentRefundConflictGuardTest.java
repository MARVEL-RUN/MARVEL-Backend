package kr.co.teambrain.marvelrun.user.payment.command.application;

import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.PaymentCancel;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentCancelCommandRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 부모 잠금을 가진 신규 업무의 환불 충돌 차단 계약을 검증한다. */
class PaymentRefundConflictGuardTest {
    private final PaymentCancelCommandRepository repository = mock(PaymentCancelCommandRepository.class);
    private final PaymentRefundConflictGuard guard = new PaymentRefundConflictGuard(repository);

    /** 환불 진행·결과불명 동안에는 새 금융 변경을 거절한다. */
    @ParameterizedTest
    @EnumSource(value = PaymentCancelStatus.class, names = {"PROCESSING", "UNKNOWN"})
    void blocksUnsettledRefund(PaymentCancelStatus status) {
        Payment payment = Payment.builder().id("p").build();
        PaymentCancel cancellation = PaymentCancel.builder().id("c").status(status).build();
        when(repository.findAllByPaymentIdsForUpdate(List.of("p"))).thenReturn(List.of(cancellation));
        assertThatThrownBy(() -> guard.validate(List.of(payment)))
                .isInstanceOfSatisfying(CustomException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_CANCEL_CONFLICT));
    }

    /** 확정 종료된 과거 취소는 새 금융 변경을 무조건 차단하지 않는다. */
    @ParameterizedTest
    @EnumSource(value = PaymentCancelStatus.class, names = {"DONE", "FAILED"})
    void allowsSettledRefundHistory(PaymentCancelStatus status) {
        Payment payment = Payment.builder().id("p").build();
        PaymentCancel cancellation = PaymentCancel.builder().id("c").status(status).build();
        when(repository.findAllByPaymentIdsForUpdate(List.of("p"))).thenReturn(List.of(cancellation));
        assertThatCode(() -> guard.validate(List.of(payment))).doesNotThrowAnyException();
    }

    /** 부모 주문이 없으면 빈 IN 쿼리를 실행하지 않는다. */
    @Test
    void emptyScopeDoesNotQuery() {
        guard.validate(List.of());
        verifyNoInteractions(repository);
    }
}