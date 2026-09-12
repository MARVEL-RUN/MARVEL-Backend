package kr.co.teambrain.marvelrun.user.payment.command.application.dto;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.TossPaymentStatus;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.payment.command.domain.Payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentConfirmResponse(

        String paymentId,

        PaymentProcessStatus paymentStatus,

        TossPaymentStatus tossStatus,

        String registrationId,

        RegistrationStatus registrationStatus,

        BigDecimal paidAmount,

        LocalDateTime approvedAt,

        String receiptUrl

) {

    public static PaymentConfirmResponse from(
            Registration registration,
            Payment payment
    ) {

        return new PaymentConfirmResponse(
                payment.getId(),
                payment.getProcessStatus(),
                payment.getTossStatus(),

                registration.getId(),
                registration.getStatus(),
                registration.getPaidAmount(),

                payment.getApprovedAt(),
                payment.getReceiptUrl()
        );
    }
}