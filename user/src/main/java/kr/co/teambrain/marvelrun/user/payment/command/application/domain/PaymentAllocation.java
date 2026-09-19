package kr.co.teambrain.marvelrun.user.payment.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import kr.co.teambrain.marvelrun.common.entity.PaymentAllocationBase;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * User 모듈의 Payment와 Registration 사이 금액 귀속을 저장한다.
 *
 * 최초 단체결제, 추가결제 등 하나의 Payment가 어느 Registration에
 * 얼마만큼 귀속되는지 보존하는 금융 원장이다.
 *
 * 저장된 Allocation은 해당 Payment의 과거 사실이므로
 * 이후 Registration.contractAmount가 변경되어도 수정하지 않는다.
 */
@Getter
@Entity
@SuperBuilder
@Table(
        name = "payment_allocation",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_payment_allocation_payment_registration",
                        columnNames = {
                                "payment_id",
                                "registration_id"
                        }
                )
        },
        indexes = {
                @Index(
                        name = "idx_payment_allocation_registration",
                        columnList = "registration_id"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentAllocation
        extends PaymentAllocationBase<
        Payment,
        Registration> {

    /**
     * 하나의 Payment 금액을 특정 Registration에 귀속시키는 Allocation을 생성한다.
     *
     * Payment 전체 금액과의 합계 검증 및 Payment 대상과 Registration의
     * 귀속 관계 검증은 PaymentAllocationCreator에서 수행한다.
     *
     * @param payment 귀속 대상 Payment
     * @param registration 금액 귀속 대상 Registration
     * @param allocatedAmount 해당 Payment에서 Registration에 귀속되는 금액
     * @return 생성된 PaymentAllocation
     */
    public static PaymentAllocation create(
            Payment payment,
            Registration registration,
            BigDecimal allocatedAmount
    ) {

        if (
                payment == null
                        || registration == null
                        || allocatedAmount == null
                        || allocatedAmount.signum() < 0
        ) {
            throw new CustomException(
                    ErrorCode.PAYMENT_ALLOCATION_INTEGRITY_ERROR,
                    " PaymentAllocation 생성 정보가 올바르지 않습니다."
            );
        }

        return PaymentAllocation.builder()
                .payment(payment)
                .registration(registration)
                .allocatedAmount(allocatedAmount)
                .build();
    }
}