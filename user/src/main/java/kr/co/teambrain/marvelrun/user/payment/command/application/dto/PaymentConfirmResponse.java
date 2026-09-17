package kr.co.teambrain.marvelrun.user.payment.command.application.dto;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.TossPaymentStatus;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.payment.command.domain.Payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record PaymentConfirmResponse(

        String paymentId,

        PaymentProcessStatus processStatus,

        TossPaymentStatus tossStatus,

        String registrationId,

        String organizationId,

        List<String> registrationIds,

        RegistrationStatus registrationStatus,

        String orderId,

        BigDecimal amount,

        LocalDateTime approvedAt,

        String receiptUrl
) {

    public static PaymentConfirmResponse fromRegistration(
            Registration registration,
            Payment payment
    ) {

        return new PaymentConfirmResponse(
                payment.getId(),
                payment.getProcessStatus(),
                payment.getTossStatus(),
                registration.getId(),
                null,
                null,
                registration.getStatus(),
                payment.getOrderId(),
                payment.getAmount(),
                payment.getApprovedAt(),
                payment.getReceiptUrl()
        );
    }


    public static PaymentConfirmResponse fromOrganization(
            String organizationId,
            List<Registration> registrations,
            Payment payment
    ) {

        List<String> registrationIds =
                registrations.stream()
                        .map(
                                Registration::getId
                        )
                        .toList();


        return new PaymentConfirmResponse(
                payment.getId(),
                payment.getProcessStatus(),
                payment.getTossStatus(),
                null,
                organizationId,
                List.copyOf(
                        registrationIds
                ),
                registrations.get(0).getStatus(),
                payment.getOrderId(),
                payment.getAmount(),
                payment.getApprovedAt(),
                payment.getReceiptUrl()
        );
    }
}