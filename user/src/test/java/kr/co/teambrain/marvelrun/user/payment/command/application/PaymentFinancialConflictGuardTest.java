package kr.co.teambrain.marvelrun.user.payment.command.application;

import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.PaymentCancel;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** 결과 반영의 자기 거래 제외가 다른 미확정 거래까지 숨기지 않는지 검증한다. */
class PaymentFinancialConflictGuardTest {
    private final PaymentFinancialConflictGuard guard = new PaymentFinancialConflictGuard();

    /** 자신의 승인 결과를 반영할 때 그 승인 시도만 제외할 수 있다. */
    @Test
    void excludesOnlyOwnPayment() {
        Payment own = Payment.builder().id("own").processStatus(PaymentProcessStatus.CONFIRMING).build();
        assertThatCode(() -> guard.validate(List.of(own), List.of(), "own", null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.validate(List.of(own), List.of(), null, null))
                .isInstanceOf(CustomException.class);
    }

    /** 자기 승인을 제외해도 다른 결과불명 취소는 충돌로 남는다. */
    @Test
    void anotherUnknownCancelStillBlocks() {
        Payment own = Payment.builder().id("own").processStatus(PaymentProcessStatus.CONFIRMING).build();
        PaymentCancel other = PaymentCancel.builder().id("other").status(PaymentCancelStatus.UNKNOWN).build();
        assertThatThrownBy(() -> guard.validate(List.of(own), List.of(other), "own", null))
                .isInstanceOf(CustomException.class);
    }

    /** 완료·명확 실패 취소는 신규 금융 동작을 무조건 막지 않는다. */
    @Test
    void completedAndFailedCancelsAreNotUnsettled() {
        PaymentCancel done = PaymentCancel.builder().id("done").status(PaymentCancelStatus.DONE).build();
        PaymentCancel failed = PaymentCancel.builder().id("failed").status(PaymentCancelStatus.FAILED).build();
        assertThatCode(() -> guard.validate(List.of(), List.of(done, failed), null, null))
                .doesNotThrowAnyException();
    }
}