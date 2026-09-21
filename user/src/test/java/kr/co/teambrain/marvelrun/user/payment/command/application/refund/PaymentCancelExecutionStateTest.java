package kr.co.teambrain.marvelrun.user.payment.command.application.refund;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.PaymentCancel;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 취소 시작 및 결과 상태의 중복·역행 방지 규칙을 검증한다. */
class PaymentCancelExecutionStateTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 20, 12, 0);

    /** 시작 시각이 기록된 시도는 다시 외부 전송 대상으로 획득하지 않는다. */
    @Test
    void claimsOnlyOnce() {
        PaymentCancel cancel = prepared();
        assertThat(cancel.startRefund(NOW)).isTrue();
        assertThat(cancel.startRefund(NOW.plusSeconds(1))).isFalse();
        assertThat(cancel.getRequestedAt()).isEqualTo(NOW);
    }

    /** 동일 거래는 중복 적용하지 않고 다른 거래로 완료 값을 교체하지 않는다. */
    @Test
    void appliesSameSuccessOnceAndRejectsDifferentTransaction() {
        PaymentCancel cancel = prepared();
        cancel.startRefund(NOW);
        assertThat(cancel.completeRefund("transaction", money("10000"), money("30000"), NOW)).isTrue();
        assertThat(cancel.completeRefund("transaction", money("10000"), money("30000"), NOW)).isFalse();
        assertThatThrownBy(() -> cancel.completeRefund("other", money("10000"), money("30000"), NOW))
                .isInstanceOf(CustomException.class);
        assertThat(cancel.recordRefundProblem(PaymentCancelStatus.UNKNOWN, "late", "늦은 오류")).isFalse();
        assertThat(cancel.getStatus()).isEqualTo(PaymentCancelStatus.DONE);
    }

    /** 결과불명을 명확한 실패로 뒤늦게 해제하지 않되 확인된 성공 증거는 반영한다. */
    @Test
    void unknownRequiresVerifiedSuccessToResolve() {
        PaymentCancel cancel = prepared();
        cancel.startRefund(NOW);
        cancel.recordRefundProblem(PaymentCancelStatus.UNKNOWN, "timeout", "응답 없음");
        assertThat(cancel.recordRefundProblem(PaymentCancelStatus.FAILED, "late", "늦은 실패")).isFalse();
        assertThat(cancel.getStatus()).isEqualTo(PaymentCancelStatus.UNKNOWN);
        assertThat(cancel.completeRefund("transaction", money("10000"), money("30000"), NOW)).isTrue();
        assertThat(cancel.getErrorCode()).isNull();
    }

    /** 확정 실패한 시도를 새 성공으로 덮어쓰지 않는다. */
    @Test
    void failedAttemptCannotBeCompleted() {
        PaymentCancel cancel = prepared();
        cancel.startRefund(NOW);
        cancel.recordRefundProblem(PaymentCancelStatus.FAILED, "rejected", "거절");
        assertThatThrownBy(() -> cancel.completeRefund("transaction", money("10000"), money("30000"), NOW))
                .isInstanceOf(CustomException.class);
    }

    /** 외부 요청을 시작하지 않은 준비 건을 완료 상태로 바꾸지 않는다. */
    @Test
    void cannotCompleteBeforeStart() {
        assertThatThrownBy(() -> prepared().completeRefund("transaction", money("10000"), money("30000"), NOW))
                .isInstanceOf(CustomException.class);
    }

    /** 테스트에 필요한 준비 상태만 구성한다. */
    private static PaymentCancel prepared() {
        return PaymentCancel.builder().id("cancel").cancelAmount(money("10000"))
                .status(PaymentCancelStatus.PROCESSING).build();
    }

    /** 정확한 십진 금액을 구성한다. */
    private static BigDecimal money(String value) { return new BigDecimal(value); }
}

