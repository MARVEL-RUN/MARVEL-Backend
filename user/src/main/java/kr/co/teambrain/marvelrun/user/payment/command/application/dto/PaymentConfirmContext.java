package kr.co.teambrain.marvelrun.user.payment.command.application.dto;

public record PaymentConfirmContext(

        String paymentId,

        String registrationId,

        String paymentKey,

        String orderId,

        long amount,

        String idempotencyKey,

        String correlationId

) {
}