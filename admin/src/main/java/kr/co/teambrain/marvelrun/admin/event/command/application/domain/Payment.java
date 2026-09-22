package kr.co.teambrain.marvelrun.admin.event.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.admin.user.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.common.entity.PaymentBase;
import lombok.Getter;
import lombok.experimental.SuperBuilder;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

@Getter
@SuperBuilder
@Entity
@Table(name = "payment")
@NoArgsConstructor(access = PROTECTED)
public class Payment extends PaymentBase<Registration, Organization> {
    /**
     * 현재 결제 상태가 신청 수정을 허용하는지 확인한다.
     *
     * 승인 진행 중이거나 결과를 확정하지 못한 결제는 수정을 차단한다.
     * 완료된 결제는 과거 금융 사실로 보존하고 후속 차액 처리에 사용한다.
     *
     * 호출자는 같은 트랜잭션에서 해당 Payment를 잠금 조회해야 한다.
     */
    public void validateRegistrationModificationAllowed() {
        if (processStatus == null) {
            throw new CustomException(
                    ErrorCode.REGISTRATION_MODIFICATION_PAYMENT_CONFLICT,
                    " Payment 처리 상태가 없습니다."
                            + " paymentId=" + getId()
            );
        }

        switch (processStatus) {
            case READY, COMPLETED, FAILED, INVALIDATED -> {
                // 해당 상태는 신청 수정 가능 상태이다.
            }

            default -> throw new CustomException(
                    ErrorCode.REGISTRATION_MODIFICATION_PAYMENT_CONFLICT,
                    " 관련 결제의 처리가 완료되지 않았습니다."
                            + " paymentId=" + getId()
                            + ", processStatus=" + processStatus
            );
        }
    }

    /**
     * 신청 수정으로 더 이상 사용하면 안 되는 READY 주문을 무효화한다.
     *
     * 금액과 PaymentAllocation은 변경하지 않는다.
     * COMPLETED / FAILED / INVALIDATED는 기존 상태를 유지한다.
     *
     * @return 이번 호출에서 READY를 무효화했다면 true
     */
    public boolean invalidateForRegistrationModification() {
        validateRegistrationModificationAllowed();

        if (processStatus != PaymentProcessStatus.READY) {
            return false;
        }

        processStatus = PaymentProcessStatus.INVALIDATED;
        return true;
    }

}
