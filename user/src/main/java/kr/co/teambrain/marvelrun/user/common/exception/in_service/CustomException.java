package kr.co.teambrain.marvelrun.user.common.exception.in_service;


import lombok.Getter;

@Getter
public class CustomException extends RuntimeException {

    private final ErrorCode errorCode;

    public CustomException(ErrorCode errorCode) {
        super(""); // 메시지 없는 예외라는 의도 표현
        this.errorCode = errorCode;
    }

    public CustomException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }

    public CustomException(ErrorCode errorCode, Error e) {
        super(errorCode.getMessage(), e);
        this.errorCode = errorCode;
    }

    public CustomException(ErrorCode errorCode, String message) {
        super(errorCode.getMessage() + message);
        this.errorCode = errorCode;
    }
}
