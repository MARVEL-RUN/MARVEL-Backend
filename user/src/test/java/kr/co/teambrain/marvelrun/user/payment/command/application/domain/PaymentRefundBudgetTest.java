package kr.co.teambrain.marvelrun.user.payment.command.application.domain;

import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 원결제 또는 원 Allocation 범위의 환불 한도와 미확정 거래 차단을 검증한다.
 *
 * 실제 조회 범위·잠금·동시성은 이 계산기를 호출하는 서비스에서 검증한다.
 */
class PaymentRefundBudgetTest {

    /**
     * 취소 이력이 없으면 원금 전체가 환불 가능하다.
     */
    @Test
    void noHistoryLeavesOriginalAmountAvailable() {
        PaymentRefundBudget budget =
                PaymentRefundBudget.calculate(money("40000"), List.of());

        assertThat(budget.remainingAmount())
                .isEqualByComparingTo("40000");
        assertThat(budget.unsettled()).isFalse();

        assertThatCode(
                () -> budget.validateRequest(money("40000"))
        ).doesNotThrowAnyException();
    }

    /**
     * 완료 환불은 한도에서 차감하고 명확한 실패는 차감하지 않는다.
     */
    @Test
    void completedRefundConsumesBudgetButFailedRefundDoesNot() {
        PaymentRefundBudget budget = PaymentRefundBudget.calculate(
                money("40000"),
                List.of(
                        cancel(PaymentCancelStatus.DONE, "10000"),
                        cancel(PaymentCancelStatus.FAILED, "20000")
                )
        );

        assertThat(budget.remainingAmount())
                .isEqualByComparingTo("30000");
        assertThat(budget.unsettled()).isFalse();

        assertThatCode(
                () -> budget.validateRequest(money("30000"))
        ).doesNotThrowAnyException();
    }

    /**
     * 진행 중과 결과불명 금액은 한도를 점유한다.
     * 잔여 한도가 있더라도 새 환불은 허용하지 않는다.
     */
    @ParameterizedTest
    @EnumSource(
            value = PaymentCancelStatus.class,
            names = {"PROCESSING", "UNKNOWN"}
    )
    void unsettledRefundReservesAmountAndBlocksNewRequest(
            PaymentCancelStatus status
    ) {
        PaymentRefundBudget budget = PaymentRefundBudget.calculate(
                money("40000"),
                List.of(cancel(status, "10000"))
        );

        assertThat(budget.remainingAmount())
                .isEqualByComparingTo("30000");
        assertThat(budget.unsettled()).isTrue();

        assertError(
                () -> budget.validateRequest(money("1000")),
                ErrorCode.PAYMENT_CANCEL_CONFLICT
        );
    }

    /**
     * 완료·진행 중·결과불명 금액을 모두 합산하며 실패분은 제외한다.
     */
    @Test
    void aggregatesAllReservedAndCompletedAmounts() {
        PaymentRefundBudget budget = PaymentRefundBudget.calculate(
                money("50000"),
                List.of(
                        cancel(PaymentCancelStatus.DONE, "10000"),
                        cancel(PaymentCancelStatus.PROCESSING, "5000"),
                        cancel(PaymentCancelStatus.UNKNOWN, "7000"),
                        cancel(PaymentCancelStatus.FAILED, "30000")
                )
        );

        assertThat(budget.remainingAmount())
                .isEqualByComparingTo("28000");
        assertThat(budget.unsettled()).isTrue();
    }

    /**
     * 원결제 잔액이 충분하더라도 해당 참가자 귀속 잔액을 넘을 수 없다.
     */
    @Test
    void allocationBudgetMustAlsoAllowTheRefund() {
        PaymentRefundBudget paymentBudget = PaymentRefundBudget.calculate(
                money("70000"),
                List.of(cancel(PaymentCancelStatus.DONE, "20000"))
        );

        PaymentRefundBudget allocationBudget = PaymentRefundBudget.calculate(
                money("30000"),
                List.of(cancel(PaymentCancelStatus.DONE, "20000"))
        );

        BigDecimal requestedAmount = money("15000");

        assertThatCode(
                () -> paymentBudget.validateRequest(requestedAmount)
        ).doesNotThrowAnyException();

        assertError(
                () -> allocationBudget.validateRequest(requestedAmount),
                ErrorCode.PAYMENT_CANCEL_AMOUNT_EXCEEDED
        );
    }

    /**
     * 여러 참가자의 환불 합계도 원결제 전체 잔액 이내여야 한다.
     */
    @Test
    void paymentBudgetMustAllowTheWholeCancelAmount() {
        PaymentRefundBudget paymentBudget = PaymentRefundBudget.calculate(
                money("70000"),
                List.of(cancel(PaymentCancelStatus.DONE, "20000"))
        );

        assertError(
                () -> paymentBudget.validateRequest(money("50001")),
                ErrorCode.PAYMENT_CANCEL_AMOUNT_EXCEEDED
        );
    }

    /**
     * 저장된 점유 금액이 원금을 초과하면 0으로 보정하지 않고 오류로 처리한다.
     */
    @Test
    void rejectsOvercommittedHistory() {
        assertError(
                () -> PaymentRefundBudget.calculate(
                        money("30000"),
                        List.of(
                                cancel(PaymentCancelStatus.DONE, "20000"),
                                cancel(PaymentCancelStatus.UNKNOWN, "10001")
                        )
                ),
                ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR
        );
    }

    /**
     * 음수 또는 누락된 원금을 허용하지 않는다.
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"-1"})
    void rejectsInvalidOriginalAmount(String value) {
        BigDecimal originalAmount = value == null ? null : money(value);

        assertError(
                () -> PaymentRefundBudget.calculate(
                        originalAmount,
                        List.of()
                ),
                ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR
        );
    }

    /**
     * 0원·음수·누락된 환불 요청을 허용하지 않는다.
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-1"})
    void rejectsInvalidRequestedAmount(String value) {
        PaymentRefundBudget budget =
                PaymentRefundBudget.calculate(money("40000"), List.of());

        BigDecimal requestedAmount = value == null ? null : money(value);

        assertError(
                () -> budget.validateRequest(requestedAmount),
                ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR
        );
    }

    /**
     * 상태나 금액이 잘못된 취소 기록을 정상 기록으로 간주하지 않는다.
     */
    @Test
    void rejectsMalformedHistory() {
        assertError(
                () -> PaymentRefundBudget.calculate(
                        money("40000"),
                        List.of(new PaymentRefundBudget.CancelAmount(
                                null,
                                money("10000")
                        ))
                ),
                ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR
        );

        assertError(
                () -> PaymentRefundBudget.calculate(
                        money("40000"),
                        List.of(cancel(PaymentCancelStatus.DONE, "-1"))
                ),
                ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR
        );
    }

    /**
     * 금액의 소수점 자릿수 차이를 한도 초과로 오인하지 않는다.
     */
    @Test
    void comparesMoneyNumericallyRegardlessOfScale() {
        PaymentRefundBudget budget = PaymentRefundBudget.calculate(
                money("40000.00"),
                List.of(cancel(PaymentCancelStatus.DONE, "10000.0"))
        );

        assertThatCode(
                () -> budget.validateRequest(money("30000"))
        ).doesNotThrowAnyException();
    }

    /**
     * 취소 상태와 해당 조회 범위에 귀속되는 금액을 구성한다.
     */
    private PaymentRefundBudget.CancelAmount cancel(
            PaymentCancelStatus status,
            String amount
    ) {
        return new PaymentRefundBudget.CancelAmount(status, money(amount));
    }

    /**
     * 예외 타입과 업무 오류 코드를 함께 검증한다.
     */
    private void assertError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        CustomException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(expected)
                );
    }

    /**
     * 부동소수점 변환 없이 테스트 금액을 생성한다.
     */
    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}