package kr.co.teambrain.marvelrun.user.payment.command.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.PaymentProcessLogBase;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_log.PaymentProcessSource;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_log.PaymentProcessType;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.Map;

import static lombok.AccessLevel.PROTECTED;

@Getter
@Entity
@SuperBuilder
@Table(
        name = "payment_process_log",
        indexes = {

                @Index(
                        name = "idx_payment_process_log_registration",
                        columnList = "registration_id"
                ),

                @Index(
                        name = "idx_payment_process_log_payment",
                        columnList = "payment_id"
                ),

                @Index(
                        name = "idx_payment_process_log_payment_cancel",
                        columnList = "payment_cancel_id"
                ),

                @Index(
                        name = "idx_payment_process_log_order_id",
                        columnList = "order_id"
                ),

                @Index(
                        name = "idx_payment_process_log_payment_key",
                        columnList = "payment_key"
                ),

                @Index(
                        name = "idx_payment_process_log_correlation",
                        columnList = "correlation_id"
                ),

                @Index(
                        name = "idx_payment_process_log_type_created",
                        columnList = "process_type,created_at"
                )
        }
)
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



    /** confirm 요청됨 */
    public static PaymentProcessLog confirmRequested(
            Payment payment,
            String correlationId
    ) {

        return PaymentProcessLog.builder()
                .registrationId(
                        payment.getRegistration().getId()
                )
                .paymentId(payment.getId())
                .orderId(payment.getOrderId())
                .paymentKey(payment.getPaymentKey())
                .idempotencyKey(
                        payment.getConfirmIdempotencyKey()
                )
                .correlationId(correlationId)

                .processType(
                        PaymentProcessType.CONFIRM_REQUESTED
                )

                .source(
                        PaymentProcessSource.API
                )

                .metadata(
                        Map.of(
                                "previousStatus",
                                PaymentProcessStatus.READY.name(),

                                "nextStatus",
                                PaymentProcessStatus.CONFIRMING.name(),

                                "amount",
                                payment.getAmount()
                        )
                )

                .build();
    }
    
    /** confirm 성공 저장 */
    public static PaymentProcessLog confirmSucceeded(
            Payment payment,
            String correlationId
    ) {

        return PaymentProcessLog.builder()
                .registrationId(
                        payment.getRegistration().getId()
                )
                .paymentId(payment.getId())
                .orderId(payment.getOrderId())
                .paymentKey(payment.getPaymentKey())
                .transactionKey(
                        payment.getApprovalTransactionKey()
                )
                .correlationId(correlationId)

                .processType(
                        PaymentProcessType.CONFIRM_SUCCEEDED
                )

                .source(
                        PaymentProcessSource.API
                )

                .httpStatus(200)

                .metadata(
                        Map.of(
                                "tossStatus",
                                payment.getTossStatus().name(),

                                "amount",
                                payment.getAmount()
                        )
                )

                .build();
    }

    /**
     * 최초 Payment 생성 완료 로그를 생성한다.
     *
     * PaymentCreator에서 Registration / Payment Entity를 직접 전달할 때 사용하는
     * 편의 Factory Method이다.
     *
     * 실제 로그 생성 로직은 paymentPrepared(...)에 위임한다.
     */
    public static PaymentProcessLog createPaymentPrepared(
            Registration registration,
            Payment payment,
            String correlationId
    ) {

        return paymentPrepared(
                registration.getId(),
                payment.getId(),
                payment.getOrderId(),
                correlationId,
                payment.getPurpose(),
                payment.getAmount()
        );
    }
}