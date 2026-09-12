package kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.dto;

public record TossPaymentConfirmRequest(

        String paymentKey,

        String orderId,

        long amount

) {
}