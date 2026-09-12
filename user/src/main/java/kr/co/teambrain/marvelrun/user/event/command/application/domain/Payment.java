package kr.co.teambrain.marvelrun.user.event.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.PaymentBase;

import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

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
}