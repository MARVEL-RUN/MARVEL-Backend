package kr.co.teambrain.marvelrun.admin.payment.command.application.dto;

import kr.co.teambrain.marvelrun.admin.payment.command.application.domain.PaymentAllocation;
import java.math.BigDecimal;

/** 사전 검증한 원결제 귀속과 이번 환불 배정 금액을 전달한다. */
public record PaymentCancelAllocationTarget(
        PaymentAllocation originalAllocation,
        BigDecimal amount
) {
}
