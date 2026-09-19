package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 *
 * 현재 구성상 단체 결제는 개별결제 금액을 취합 후 1개의 payment로 재구성하여 단체장이 일괄결제처리하는 시스템으로 구성되어있다.
 * 이로 인해 단체 내 개별 인원이 종목이나 기념품 등을 수정하여 발생하는 부가적인
 *
 * 하나의 Payment 금액이 어느 Registration에 얼마만큼 귀속되는지를 저장한다.
 *
 * 단체 Payment처럼 실제 결제 주체가 Organization이더라도
 * 금융 금액의 최종 귀속 Registration을 잃지 않도록 한다.
 *
 * Payment 또는 Registration 자체의 상태를 관리하는 엔티티가 아니며,
 * 결제 금액의 귀속 원장 역할만 수행한다.
 *
 * @param <P> 귀속 대상 Payment 타입
 * @param <R> 금액이 귀속되는 Registration 타입
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

    /**
     * 실제 결제 건.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "payment_id",
            nullable = false
    )
    protected P payment;

    /**
     * 해당 Payment 금액이 귀속되는 참가 신청.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "registration_id",
            nullable = false
    )
    protected R registration;

    /**
     * 해당 Registration에 귀속되는 금액.
     *
     * 최초 단체 신청에서는 Registration.contractAmount와 동일하다.
     * 향후 추가결제에서는 해당 추가결제의 귀속 금액을 저장한다.
     *
     * 0원 계약이 존재할 가능성을 막지 않기 위해 0은 허용하고,
     * 음수 금액만 금지한다.
     */
    @Column(
            name = "allocated_amount",
            nullable = false,
            precision = 12,
            scale = 2
    )
    protected BigDecimal allocatedAmount;
}