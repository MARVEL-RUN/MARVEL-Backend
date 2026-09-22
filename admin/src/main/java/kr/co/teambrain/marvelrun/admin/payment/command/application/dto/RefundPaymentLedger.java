package kr.co.teambrain.marvelrun.admin.payment.command.application.dto;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.PaymentCancel;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.admin.payment.command.application.domain.PaymentAllocation;
import kr.co.teambrain.marvelrun.admin.payment.command.application.domain.PaymentCancelAllocation;
import java.util.List;

/** 원 Payment 잠금을 보유한 상태에서 현재 읽기로 구성한 환불 계산 원장이다. */
public record RefundPaymentLedger(Payment payment, List<PaymentAllocation> allocations,
                                  List<PaymentCancel> cancellations, List<PaymentCancelAllocation> cancelAllocations) {
    /** 계산 중 목록 구조가 바뀌지 않도록 복사한다. */
    public RefundPaymentLedger {
        allocations = List.copyOf(allocations);
        cancellations = List.copyOf(cancellations);
        cancelAllocations = List.copyOf(cancelAllocations);
    }
}
