package kr.co.teambrain.marvelrun.user.payment.command.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record PaymentConfirmRequest(

        @NotBlank
        String paymentKey,

        @NotBlank
        String orderId,

        @Positive
        long amount

) {
}