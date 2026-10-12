package kr.co.teambrain.marvelrun.user.common.exception.in_service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.teambrain.marvelrun.user.common.exception.out_service.PublicError;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 업무 예외의 내부 진단 보존과 공개 메시지·마스킹·응답 계약을 검증한다. */
class ErrorResponseTest {

    /** 기존 생성자 4종은 진단 문자열과 원인을 보존하면서 기본 응답만 제공한다. */
    @Test
    void existingConstructorsKeepDiagnosticsOutOfResponse() {
        // 기존 생성 경로별로 서로 다른 내부 상세와 원인을 준비한다.
        ErrorCode code = ErrorCode.REGISTRATION_PERIOD_BLOCKED;
        Throwable cause = new IllegalStateException("내부 원인");
        Error error = new AssertionError("내부 Error");
        CustomException basic = new CustomException(code);
        CustomException diagnostic = new CustomException(code, " reservationId=internal-id");
        CustomException caused = new CustomException(code, cause);
        CustomException errored = new CustomException(code, error);

        // 응답은 모든 기존 생성자에서 기본 메시지와 기존 필드를 유지한다.
        for (CustomException exception : List.of(basic, diagnostic, caused, errored)) {
            ResponseEntity<ErrorResponse> response = new GlobalExceptionHandler().handleCustomException(exception);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody().getCode()).isEqualTo(code.name());
            assertThat(response.getBody().getMessage()).isEqualTo(code.getMessage());
            assertThat(response.getBody().getErrors()).isNull();
        }

        // 내부 진단 계약도 응답 메시지 추가 때문에 바뀌지 않는다.
        assertThat(basic.getMessage()).isEmpty();
        assertThat(diagnostic.getMessage()).isEqualTo(code.getMessage() + " reservationId=internal-id");
        assertThat(caused.getCause()).isSameAs(cause);
        assertThat(errored.getCause()).isSameAs(error);
    }

    /** 명시적 메시지는 기본 문구 중복 없이 기존 JSON 구조로 반환한다. */
    @Test
    void explicitResponseMessageUsesExistingHandlerAndJsonContract() {
        // 특정 정책 코드에 종속되지 않는 공통 메시지 전달을 준비한다.
        ErrorCode code = ErrorCode.EVENT_REGISTRATION_CLOSED;
        String message = "개발자가 지정한 공개 안내입니다.";
        CustomException exception = CustomException.withResponseMessage(code, message);

        // 동일 핸들러와 직렬화 경로의 공개 메시지 및 필드를 검증한다.
        ResponseEntity<ErrorResponse> response = new GlobalExceptionHandler().handleCustomException(exception);
        assertThat(response.getStatusCode()).isEqualTo(code.getHttpStatus());
        JsonNode actual = new ObjectMapper().valueToTree(response.getBody());
        JsonNode expected = new ObjectMapper().createObjectNode()
                .put("httpStatus", code.getHttpStatus().name())
                .put("code", code.name())
                .put("message", message)
                .putNull("errors");
        assertThat(actual).isEqualTo(expected);
    }

    /** 민감 코드에는 명시적 공개 문구가 있어도 기존 마스킹이 우선한다. */
    @Test
    void sensitiveErrorsRemainMaskedForBothCreationPaths() {
        // 기본 예외와 명시적 메시지 예외에 같은 민감 코드를 사용한다.
        ErrorCode code = ErrorCode.USER_CANNOT_CHANGE_OWN_INFO_IN_REGISTRATION_PATCH;
        List<CustomException> exceptions = List.of(new CustomException(code),
                CustomException.withResponseMessage(code, "노출되지 않아야 할 안내"));

        // 메시지 지정 여부와 무관하게 상태·코드·문구를 마스킹한다.
        for (CustomException exception : exceptions) {
            ResponseEntity<ErrorResponse> response = new GlobalExceptionHandler().handleCustomException(exception);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().getCode()).isEqualTo(PublicError.REQUEST_DENIED.name());
            assertThat(response.getBody().getMessage()).isEqualTo(PublicError.REQUEST_DENIED.getMessage());
            assertThat(response.getBody().getErrors()).isNull();
        }
    }
}
