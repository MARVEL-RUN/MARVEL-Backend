package kr.co.teambrain.marvelrun.admin.payment.command.infrastructure.toss.refund;

import java.math.BigDecimal;

/** 전액 환불도 금액을 명시하여 의도하지 않은 잔액 전체 취소를 방지한다. */
public record TossCancelRequest(String cancelReason, BigDecimal cancelAmount) {
}