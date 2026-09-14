package kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.dto;


import java.time.OffsetDateTime;

public record TossPaymentConfirmResponse(

        String paymentKey,

        String orderId,

        String orderName,

        String status,

        String method,

        long totalAmount,

        OffsetDateTime requestedAt,

        OffsetDateTime approvedAt,

        String lastTransactionKey,

        EasyPay easyPay,

        Receipt receipt

) {

    public record EasyPay(

            String provider,

            Long amount,

            Long discountAmount

    ) {
    }


    public record Receipt(

            String url

    ) {
    }
}