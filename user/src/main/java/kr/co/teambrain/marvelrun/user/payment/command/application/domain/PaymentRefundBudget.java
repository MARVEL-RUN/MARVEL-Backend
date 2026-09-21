package kr.co.teambrain.marvelrun.user.payment.command.application.domain;

import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;

import java.math.BigDecimal;
import java.util.List;

/**
 * 원결제 또는 원 Allocation 범위의 환불 한도를 계산한 불변 값이다.
 *
 * DB 엔티티가 아니며 조회·잠금·PG 호출을 수행하지 않는다.
 * 호출 서비스가 동일 범위의 최신 취소 기록을 중복 없이 전달해야 한다.
 *
 * 원결제 전체 한도와 참가자별 귀속 한도에 각각 같은 계산 규칙을 적용한다.
 */
public record PaymentRefundBudget(
        BigDecimal remainingAmount,
        boolean unsettled
) {

    /**
     * 완료·진행 중·결과불명 취소 금액을 원금에서 제외한다.
     *
     * FAILED는 한도를 점유하지 않는다.
     * PROCESSING 또는 UNKNOWN이 있으면 새 환불 요청을 차단하도록 표시한다.
     */
    public static PaymentRefundBudget calculate(
            BigDecimal originalAmount,
            List<CancelAmount> cancellations
    ) {
        if (originalAmount == null
                || originalAmount.signum() < 0
                || cancellations == null) {
            throw integrityError();
        }

        BigDecimal committedAmount = BigDecimal.ZERO;
        boolean hasUnsettled = false;

        for (CancelAmount cancellation : cancellations) {
            if (cancellation == null
                    || cancellation.status() == null
                    || cancellation.amount() == null
                    || cancellation.amount().signum() <= 0) {
                throw integrityError();
            }

            switch (cancellation.status()) {
                case DONE -> committedAmount =
                        committedAmount.add(cancellation.amount());

                case PROCESSING, UNKNOWN -> {
                    committedAmount =
                            committedAmount.add(cancellation.amount());
                    hasUnsettled = true;
                }

                case FAILED -> {
                    // 명확하게 실패한 취소는 환불 한도를 점유하지 않는다.
                }
            }
        }

        BigDecimal remaining = originalAmount.subtract(committedAmount);

        if (remaining.signum() < 0) {
            throw integrityError();
        }

        return new PaymentRefundBudget(remaining, hasUnsettled);
    }

    /**
     * 미확정 거래가 없고 요청 금액이 남은 한도 이내인지 검증한다.
     *
     * 이 검증만으로 권한·원결제 승인 여부·업무상 환불 필요성이 보장되지는 않는다.
     * 실제 요청은 상위 서비스의 잠금과 업무 검증 안에서 수행해야 한다.
     */
    public void validateRequest(BigDecimal requestedAmount) {
        if (remainingAmount == null
                || remainingAmount.signum() < 0
                || requestedAmount == null
                || requestedAmount.signum() <= 0) {
            throw integrityError();
        }

        if (unsettled) {
            throw new CustomException(
                    ErrorCode.PAYMENT_CANCEL_CONFLICT
            );
        }

        if (requestedAmount.compareTo(remainingAmount) > 0) {
            throw new CustomException(
                    ErrorCode.PAYMENT_CANCEL_AMOUNT_EXCEEDED
            );
        }
    }

    /**
     * 내부 환불 기록이나 계산 입력의 정합성 오류를 생성한다.
     */
    private static CustomException integrityError() {
        return new CustomException(
                ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR
        );
    }

    /**
     * 계산 대상 범위에 귀속되는 취소 상태와 금액이다.
     *
     * 원결제 기준 계산에는 취소 총액을,
     * 원 Allocation 기준 계산에는 해당 취소 귀속 금액을 전달한다.
     * 두 종류를 한 목록에 섞으면 안 된다.
     */
    public record CancelAmount(
            PaymentCancelStatus status,
            BigDecimal amount
    ) {
    }
}