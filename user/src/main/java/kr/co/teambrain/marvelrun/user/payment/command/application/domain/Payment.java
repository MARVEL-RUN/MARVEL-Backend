package kr.co.teambrain.marvelrun.user.payment.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.PaymentBase;

import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentMethod;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;

import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.TossPaymentStatus;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.dto.TossPaymentConfirmResponse;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@SuperBuilder
@Table(name = "payment")
@NoArgsConstructor(access = PROTECTED)
public class Payment
        extends PaymentBase<Registration, Organization> {

    public static Payment createInitial(
            Registration registration,
            String orderId,
            String orderName,
            BigDecimal amount,
            PaymentPurpose purpose,
            String confirmIdempotencyKey
    ) {

        return Payment.builder()
                .registration(
                        registration
                )
                .organization(
                        null
                )

                .orderId(orderId)
                .orderName(orderName)

                .amount(amount)
                .purpose(purpose)

                .processStatus(
                        PaymentProcessStatus.READY
                )

                .confirmIdempotencyKey(
                        confirmIdempotencyKey
                )

                .build();
    }

    public static Payment createOrgInitial(
            Organization organization,
            BigDecimal amount,
            String orderId,
            String orderName,
            PaymentPurpose purpose,
            String confirmIdempotencyKey
    ) {

        return Payment.builder()

                .registration(
                        null
                )

                .organization(
                        organization
                )

                .orderId(orderId)
                .orderName(orderName)

                .amount(amount)
                .purpose(purpose)

                .processStatus(
                        PaymentProcessStatus.READY
                )

                .confirmIdempotencyKey(
                        confirmIdempotencyKey
                )

                .build();
    }


    public void startConfirm(
            String paymentKey
    ) {

        this.paymentKey = paymentKey;
        this.processStatus =
                PaymentProcessStatus.CONFIRMING;
    }

    public void completeConfirm(
            TossPaymentConfirmResponse response
    ) {

        this.processStatus =
                PaymentProcessStatus.COMPLETED;

        this.tossStatus =
                TossPaymentStatus.DONE;

        this.paymentMethod =
                resolvePaymentMethod(
                        response.method()
                );

        this.easyPayProvider =
                response.easyPay() != null
                        ? response.easyPay().provider()
                        : null;

        this.approvalTransactionKey =
                response.lastTransactionKey();

        this.tossRequestedAt =
                toLocalDateTime(
                        response.requestedAt()
                );

        this.approvedAt =
                toLocalDateTime(
                        response.approvedAt()
                );

        this.receiptUrl =
                response.receipt() != null
                        ? response.receipt().url()
                        : null;
    }

    public void failConfirm() {

        this.processStatus =
                PaymentProcessStatus.FAILED;
    }

    /**
     * 미결제 확보 반환을 위해 아직 승인하지 않은 주문을 무효화한다.
     *
     * READY는 INVALIDATED로 전환한다.
     * FAILED 또는 INVALIDATED는 이미 승인할 수 없으므로 변경하지 않는다.
     *
     * CONFIRMING, UNKNOWN, COMPLETED는 미결제 반환을 허용하지 않는다.
     * 호출자는 같은 트랜잭션에서 해당 Payment를 잠금 조회해야 한다.
     *
     * @return 이번 호출에서 주문이 무효화되었으면 true
     */
    public boolean invalidateForReservationRelease() {

        if (
                processStatus == PaymentProcessStatus.FAILED
                        || processStatus == PaymentProcessStatus.INVALIDATED
        ) {
            return false;
        }

        /*
         * 승인 진행 중이거나 결과를 확정하지 못한 주문은
         * 확보를 반환하기 전에 결제 결과부터 해결해야 한다.
         *
         * 이미 승인 완료된 주문 역시 미결제 반환 대상이 아니다.
         */
        if (processStatus != PaymentProcessStatus.READY) {
            throw new CustomException(
                    ErrorCode.PAYMENT_NOT_CONFIRMABLE,
                    " 현재 결제 상태에서는 미결제 확보를 반환할 수 없습니다."
                            + " paymentId=" + getId()
                            + ", processStatus=" + processStatus
            );
        }

        this.processStatus = PaymentProcessStatus.INVALIDATED;

        return true;
    }

    
    /* 정상적으로 종료된 것인지 불명확한경우 */
    public void markConfirmUnknown() {

        this.processStatus =
                PaymentProcessStatus.UNKNOWN;
    }

    /** 타겟 검증 - 개인신청 기준인지 */
    public boolean isRegistrationPayment() {

        return registration != null;
    }

    /** 타겟 검증 - 단체신청 기준인지 */
    public boolean isOrgPayment() {

        return organization != null;
    }


    /*
    *
    * inner method
    *
    * */

    private PaymentMethod resolvePaymentMethod(
            String tossMethod
    ) {

        if ("카드".equals(tossMethod)) {
            return PaymentMethod.CARD;
        }

        if ("간편결제".equals(tossMethod)) {
            return PaymentMethod.EASY_PAY;
        }

        return null;
    }

    private LocalDateTime toLocalDateTime(
            OffsetDateTime dateTime
    ) {

        return dateTime != null
                ? dateTime.toLocalDateTime()
                : null;
    }
}