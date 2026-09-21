package kr.co.teambrain.marvelrun.user.payment.command.application.domain;

import jakarta.persistence.*;
import kr.co.teambrain.marvelrun.common.entity.PaymentCancelAllocationBase;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.PaymentCancel;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/** 원결제 귀속을 보존하면서 취소 시도별 참가자 환불 금액을 기록한다. */
@Getter
@Entity
@SuperBuilder
@Table(name = "payment_cancel_allocation",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_cancel_allocation_cancel_original",
                columnNames = {"payment_cancel_id", "payment_allocation_id"}),
        indexes = @Index(name = "idx_cancel_allocation_original",
                columnList = "payment_allocation_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentCancelAllocation extends PaymentCancelAllocationBase<
        PaymentCancel, PaymentAllocation> {

    /**
     * 동일 원 Payment에 속하는 Allocation에 양수 환불 금액을 귀속시킨다.
     * 과거 환불과 진행 중 환불을 포함한 남은 한도는 호출 서비스가 따로 검증한다.
     */
    public static PaymentCancelAllocation create(
            PaymentCancel cancellation,
            PaymentAllocation original,
            BigDecimal amount
    ) {
        if (cancellation == null || cancellation.getId() == null
                || original == null || original.getId() == null
                || cancellation.getPayment() == null
                || cancellation.getPayment().getId() == null
                || original.getPayment() == null
                || !cancellation.getPayment().getId().equals(original.getPayment().getId())
                || amount == null || amount.signum() <= 0
                || amount.stripTrailingZeros().scale() > 2
                || amount.compareTo(new BigDecimal("9999999999.99")) > 0
                || original.getAllocatedAmount() == null
                || amount.compareTo(original.getAllocatedAmount()) > 0) {
            throw new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
        }
        return PaymentCancelAllocation.builder()
                .paymentCancel(cancellation)
                .originalAllocation(original)
                .allocatedAmount(amount)
                .build();
    }
}