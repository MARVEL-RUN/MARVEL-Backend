package kr.co.teambrain.marvelrun.admin.payment.command.infrastructure.toss.refund;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 이번 요청으로 확인된 외부 취소의 증거이며, DB 반영 완료를 의미하지 않는다. */
public record VerifiedTossCancellation(
        String transactionKey, BigDecimal cancelAmount,
        BigDecimal refundableAmount, OffsetDateTime canceledAt,
        String paymentStatus) {
}