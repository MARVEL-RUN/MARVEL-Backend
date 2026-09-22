package kr.co.teambrain.marvelrun.admin.common.exception;

import lombok.Getter;

@Getter
public class CustomException
        extends RuntimeException {

    private final ErrorCode errorCode;


    public CustomException(
            ErrorCode errorCode
    ) {

        super(
                errorCode.getMessage()
        );

        this.errorCode =
                errorCode;
    }
    /** 사용자와 동일한 계산·정원 코드의 상세 오류를 보존한다. */
    public CustomException(ErrorCode errorCode, String message) {
        super(errorCode.getMessage() + message);
        this.errorCode = errorCode;
    }
    /** 원인 예외를 보존하는 기존 사용자 계약이다. */
    public CustomException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }
}
