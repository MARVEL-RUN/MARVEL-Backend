package kr.co.teambrain.marvelrun.user.payment.command.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.PaymentBase;

import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentMethod;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;

import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.TossPaymentStatus;
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
        extends PaymentBase<Registration> {

    public static Payment prepare(
            Registration registration,
            String orderId,
            String orderName,
            BigDecimal amount,
            PaymentPurpose purpose,
            String confirmIdempotencyKey
    ) {

        return Payment.builder()
                .registration(registration)

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

    
    /* 정상적으로 종료된 것인지 불명확한경우 */
    public void markConfirmUnknown() {

        this.processStatus =
                PaymentProcessStatus.UNKNOWN;
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