package kr.co.teambrain.marvelrun.user.common.exception.in_service;


import lombok.Getter;
import java.util.Objects;

/** 내부 진단 메시지와 클라이언트에 공개할 응답 메시지를 구분하는 업무 예외다. */
@Getter
public class CustomException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String responseMessage;

    /**
     * 추가 설명이나 원인 예외 없이 ErrorCode의 기본 안내만 반환할 때 사용한다.
     * 사용: {@code throw new CustomException(errorCode);}
     * getMessage()는 빈 문자열이며, getResponseMessage()는 enum의 기본 문구다.
     * 민감 오류에 대한 실제 HTTP 응답은 ErrorResponse의 마스킹 정책을 따른다.
     */
    public CustomException(ErrorCode errorCode) {
        super(""); // 내부 예외 메시지는 비우고 응답용 기본 문구는 별도로 보관한다.
        this.errorCode = errorCode;
        this.responseMessage = errorCode.getMessage();
    }

    /**
     * 다른 예외를 업무 오류로 변환하면서 원인과 스택 추적을 보존할 때 사용한다.
     * 사용: {@code throw new CustomException(errorCode, cause);}
     * getCause()로 원인을 확인할 수 있으며, 두 메시지 필드는 enum의 기본 문구다.
     * 원인 예외의 메시지는 응답에 추가하지 않으며 민감 오류는 별도로 마스킹한다.
     */
    public CustomException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
        this.responseMessage = errorCode.getMessage();
    }

    /**
     * Error 타입의 원인을 보존하는 기존 호출부용 오버로드다.
     * 사용: {@code throw new CustomException(errorCode, error);}
     * Throwable 생성자와 동일하게 원인을 보존하고 두 메시지 필드에는 enum 기본 문구를 저장한다.
     * Error의 상세 내용은 응답에 추가하지 않으며 민감 오류는 별도로 마스킹한다.
     */
    public CustomException(ErrorCode errorCode, Error e) {
        super(errorCode.getMessage(), e);
        this.errorCode = errorCode;
        this.responseMessage = errorCode.getMessage();
    }

    /**
     * 예약 식별자나 처리 상태 등의 내부 진단 내용을 예외 메시지에 덧붙일 때 사용한다.
     * 사용: {@code throw new CustomException(errorCode, " reservationId=" + reservationId);}
     * getMessage()는 enum 기본 문구 + message이며, 구분 공백 등은 호출부에서 지정한다.
     * getResponseMessage()는 enum 기본 문구만 유지하므로 추가 문자열은 응답에 노출되지 않는다.
     * 사용자에게 보여줄 문구를 지정하려면 withResponseMessage()를 사용한다.
     */
    public CustomException(ErrorCode errorCode, String message) {
        super(errorCode.getMessage() + message);
        this.errorCode = errorCode;
        this.responseMessage = errorCode.getMessage();
    }

    /**
     * 내부 예외 메시지와 공개 응답 메시지를 각각 초기화하는 클래스 내부 전용 생성자다.
     * diagnosticMessage는 getMessage(), responseMessage는 getResponseMessage()에 그대로 저장한다.
     * enum 기본 문구를 자동으로 붙이지 않으며 responseMessage가 null이면 생성할 수 없다.
     * 외부 호출부는 직접 접근하지 않고 withResponseMessage()를 통해 사용한다.
     */
    private CustomException(ErrorCode errorCode, String diagnosticMessage, String responseMessage) {
        super(diagnosticMessage);
        this.errorCode = errorCode;
        this.responseMessage = Objects.requireNonNull(responseMessage, "응답 메시지는 필수입니다.");
    }

    /**
     * KST 제한 기간 등 사용자가 확인할 동적 안내를 HTTP 응답 메시지로 제공할 때 사용한다.
     * 사용: {@code throw CustomException.withResponseMessage(errorCode, responseMessage);}
     * responseMessage는 접미사가 아닌 완성된 문구이며 enum 기본 문구가 필요하면 호출부에서 조합한다.
     * 지정한 문구는 getMessage()와 getResponseMessage()에 동일하게 저장되므로 내부 진단 정보는 넣지 않는다.
     * 오류 코드와 HTTP 상태는 ErrorCode를 따르고, 민감 오류는 지정 문구보다 마스킹이 우선한다.
     */
    public static CustomException withResponseMessage(ErrorCode errorCode, String responseMessage) {
        return new CustomException(errorCode, responseMessage, responseMessage);
    }
}
