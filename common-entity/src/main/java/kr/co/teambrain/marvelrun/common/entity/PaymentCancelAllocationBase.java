package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * 한 취소 시도의 금액이 어느 원결제 Allocation에 귀속되는지 보존한다.
 * 신청과 원결제는 originalAllocation을 통해 추적하며 직접 중복 저장하지 않는다.
 * 귀속 행은 변경하지 않고 취소 성공 여부는 상위 PaymentCancel 상태로 판단한다.
 */
@Getter
@MappedSuperclass
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class PaymentCancelAllocationBase<
        C extends PaymentCancelBase<?>,
        A extends PaymentAllocationBase<?, ?>> {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    protected String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_cancel_id", nullable = false, updatable = false)
    protected C paymentCancel;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_allocation_id", nullable = false, updatable = false)
    protected A originalAllocation;

    @Column(name = "allocated_amount", nullable = false,
            precision = 12, scale = 2, updatable = false)
    protected BigDecimal allocatedAmount;
}