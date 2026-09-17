package kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.exception;

import lombok.Getter;

@Getter
public class TossPaymentApiException
        extends RuntimeException {

    private final int httpStatus;

    private final String tossErrorCode;

    private final String tossErrorMessage;


    public TossPaymentApiException(
            int httpStatus,
            String tossErrorCode,
            String tossErrorMessage
    ) {

        super(tossErrorMessage);

        this.httpStatus = httpStatus;
        this.tossErrorCode = tossErrorCode;
        this.tossErrorMessage = tossErrorMessage;
    }
}
