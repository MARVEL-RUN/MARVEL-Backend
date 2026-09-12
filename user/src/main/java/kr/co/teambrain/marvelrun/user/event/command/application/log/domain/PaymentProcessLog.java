package kr.co.teambrain.marvelrun.user.event.command.application.log.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.PaymentProcessLogBase;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_log.PaymentProcessSource;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_log.PaymentProcessType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.Map;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@SuperBuilder
@Table(name = "payment_process_log")
@NoArgsConstructor(access = PROTECTED)
public class PaymentProcessLog extends PaymentProcessLogBase {

    public static PaymentProcessLog paymentPrepared(
            String registrationId,
            String paymentId,
            String orderId,
            String correlationId,
            PaymentPurpose purpose,
            BigDecimal amount
    ) {

        return PaymentProcessLog.builder()
                .registrationId(registrationId)
                .paymentId(paymentId)
                .orderId(orderId)
                .correlationId(correlationId)
                .processType(PaymentProcessType.PAYMENT_PREPARED)
                .source(PaymentProcessSource.API)
                .metadata(
                        Map.of(
                                "paymentPurpose", purpose.name(),
                                "amount", amount
                        )
                )
                .build();
    }
}