package kr.co.teambrain.marvelrun.user.payment.command.application.dto;

import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.payment.command.domain.Payment;

import java.math.BigDecimal;
import java.util.List;

public record PaymentConfirmResponse(

        String paymentId,

        String registrationId,

        String organizationId,

        List<String> registrationIds,

        String orderId,

        BigDecimal amount,

        PaymentProcessStatus processStatus
) {

    public static PaymentConfirmResponse fromRegistration(
            String registrationId,
            Payment payment
    ) {

        return new PaymentConfirmResponse(
                payment.getId(),
                registrationId,
                null,
                null,
                payment.getOrderId(),
                payment.getAmount(),
                payment.getProcessStatus()
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
                null,
                organizationId,
                List.copyOf(
                        registrationIds
                ),
                payment.getOrderId(),
                payment.getAmount(),
                payment.getProcessStatus()
        );
    }
}