package kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.refund;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 과거 취소를 제외한 신규 한 건과 잔액을 대조하여 잘못된 환불 귀속을 차단한다. */
@Component
public class TossCancelResponseVerifier {

    /** 하나라도 불일치하면 성공으로 간주하지 않는다. 조회나 상태 변경은 수행하지 않는다. */
    public Optional<VerifiedTossCancellation> verify(
            TossCancelAttempt attempt, TossCancelResponse response) {
        if (response == null || !attempt.paymentKey().equals(response.paymentKey())
                || !attempt.orderId().equals(response.orderId())
                || !"KRW".equals(response.currency())
                || !("카드".equals(response.method()) || "간편결제".equals(response.method()))
                || !same(attempt.originalAmount(), response.totalAmount())
                || response.balanceAmount() == null || response.balanceAmount().signum() < 0
                || response.cancels() == null
                || response.cancels().size() != attempt.completedCancels().size() + 1) {
            return Optional.empty();
        }
        Set<String> seen = new HashSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        TossCancelResponse.Cancel current = null;
        for (TossCancelResponse.Cancel cancel : response.cancels()) {
            if (cancel == null || cancel.transactionKey() == null
                    || cancel.transactionKey().isBlank() || cancel.transactionKey().length() > 64
                    || !seen.add(cancel.transactionKey()) || !"DONE".equals(cancel.cancelStatus())
                    || cancel.cancelAmount() == null || cancel.cancelAmount().signum() <= 0
                    || cancel.canceledAt() == null) {
                return Optional.empty();
            }
            sum = sum.add(cancel.cancelAmount());
            BigDecimal historicalAmount = attempt.completedCancels().get(cancel.transactionKey());
            if (historicalAmount != null) {
                if (!same(historicalAmount, cancel.cancelAmount())) {
                    return Optional.empty();
                }
            } else {
                if (current != null) {
                    return Optional.empty();
                }
                current = cancel;
            }
        }
        if (current == null || !seen.containsAll(attempt.completedCancels().keySet())
                || !current.transactionKey().equals(response.lastTransactionKey())
                || !same(current.cancelAmount(), attempt.cancelAmount())
                || !same(current.refundableAmount(), response.balanceAmount())
                || !same(sum.add(response.balanceAmount()), attempt.originalAmount())) {
            return Optional.empty();
        }
        String expectedStatus = response.balanceAmount().signum() == 0
                ? "CANCELED" : "PARTIAL_CANCELED";
        if (!expectedStatus.equals(response.status())) {
            return Optional.empty();
        }
        return Optional.of(new VerifiedTossCancellation(current.transactionKey(),
                current.cancelAmount(), current.refundableAmount(), current.canceledAt(),
                response.status()));
    }

    /** 소수 자릿수 표현 차이를 허용하면서 정확한 금액을 비교한다. */
    private static boolean same(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }
}