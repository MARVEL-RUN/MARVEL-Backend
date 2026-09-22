package kr.co.teambrain.marvelrun.admin.payment.command.infrastructure.toss.refund;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** 취소 응답에서 원결제 식별값, 잔액, 전체 취소 이력을 받는다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossCancelResponse(
        String paymentKey, String orderId, String currency, String method,
        String status, BigDecimal totalAmount, BigDecimal balanceAmount,
        String lastTransactionKey, List<Cancel> cancels) {

    /** 취소 이력 한 건을 표현하며, 누락 값은 검증 계층에서 결과불명으로 판정한다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cancel(String transactionKey, BigDecimal cancelAmount,
                         BigDecimal refundableAmount, String cancelStatus,
                         OffsetDateTime canceledAt) {
    }
}