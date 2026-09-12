package kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.exception;

public class TossPaymentTransportException
        extends RuntimeException {

    public TossPaymentTransportException(
            Throwable cause
    ) {

        super(
                "Toss Payments 통신 과정에서 응답을 확인하지 못했습니다.",
                cause
        );
    }
}