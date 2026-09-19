package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * 하나의 Payment 금액이 특정 Registration에 얼마만큼 귀속되는지를 정의한다.
 *
 * Payment 자체의 결제 주체가 개인 Registration이거나 Organization이더라도,
 * 실제 금융 금액이 어느 참가 신청에 귀속되는지를 별도로 보존한다.
 *
 * allocatedAmount는 현재 Registration.contractAmount가 아니라
 * 해당 Payment가 생성될 당시 그 Registration에 귀속된 금액이다.
 *
 * @param <P> 귀속 대상 Payment 타입
 * @param <R> 금액 귀속 대상 Registration 타입
 */
@Getter
@MappedSuperclass
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class PaymentAllocationBase<
        P extends PaymentBase<?, ?>,
        R extends RegistrationBase<?, ?, ?, ?, ?>> {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(
            name = "id",
            nullable = false,
            length = 40
    )
    protected String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "payment_id",
            nullable = false
    )
    protected P payment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "registration_id",
            nullable = false
    )
    protected R registration;

    @Column(
            name = "allocated_amount",
            nullable = false,
            precision = 12,
            scale = 2
    )
    protected BigDecimal allocatedAmount;
}