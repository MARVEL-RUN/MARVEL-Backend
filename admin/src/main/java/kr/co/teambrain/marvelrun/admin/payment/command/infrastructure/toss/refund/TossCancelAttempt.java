package kr.co.teambrain.marvelrun.admin.payment.command.infrastructure.toss.refund;

import java.math.BigDecimal;
import java.util.Map;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;

/** 잠금 안에서 확정하고 커밋한 환불 요청 정보를 외부 통신 계층에 전달한다. */
public record TossCancelAttempt(
        String paymentCancelId, String paymentKey, String orderId,
        String idempotencyKey, String cancelReason,
        BigDecimal originalAmount, BigDecimal cancelAmount,
        Map<String, BigDecimal> completedCancels) {

    /** 원결제의 성공 취소 이력을 복사하고, 외부 호출 전에 금액과 필수 정보를 검사한다. */
    public TossCancelAttempt {
        if (blank(paymentCancelId) || blank(paymentKey) || paymentKey.length() > 200
                || blank(orderId) || blank(idempotencyKey) || idempotencyKey.length() > 300
                || blank(cancelReason) || cancelReason.length() > 200
                || !positiveWon(originalAmount) || !positiveWon(cancelAmount)
                || completedCancels == null) {
            throw new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
        }
        BigDecimal completedAmount = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> entry : completedCancels.entrySet()) {
            if (blank(entry.getKey()) || entry.getKey().length() > 64
                    || !positiveWon(entry.getValue())) {
                throw new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
            }
            completedAmount = completedAmount.add(entry.getValue());
        }
        if (completedAmount.add(cancelAmount).compareTo(originalAmount) > 0) {
            throw new CustomException(ErrorCode.PAYMENT_CANCEL_AMOUNT_EXCEEDED);
        }
        completedCancels = Map.copyOf(completedCancels);
    }

    /** 원화 결제 금액으로 표현 가능한 양의 정수인지 확인한다. */
    private static boolean positiveWon(BigDecimal amount) {
        return amount != null && amount.signum() > 0
                && amount.stripTrailingZeros().scale() <= 0
                && amount.compareTo(new BigDecimal("10000000000")) < 0;
    }

    /** 필수 식별자와 사유의 공백 여부를 검사한다. */
    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}