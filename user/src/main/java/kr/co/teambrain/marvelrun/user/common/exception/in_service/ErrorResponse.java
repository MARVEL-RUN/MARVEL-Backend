package kr.co.teambrain.marvelrun.user.common.exception.in_service;

import kr.co.teambrain.marvelrun.user.common.exception.out_service.MemberError;
import kr.co.teambrain.marvelrun.user.common.exception.out_service.PublicError;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

@Getter
@Slf4j
public class ErrorResponse {

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
    private final List<MemberError> errors;

    @Builder
    public ErrorResponse(HttpStatus httpStatus, String code, String message, List<MemberError> errors) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
        this.errors = errors;
    }

    public ErrorResponse(ErrorCode errorCode) {
        this.httpStatus = errorCode.getHttpStatus();
        this.code = errorCode.name();
        this.message = errorCode.getMessage();
        this.errors = null;
    }

    /** 민감 오류는 마스킹하고 그 외에는 업무 코드와 명시적인 공개 응답 메시지를 반환한다. */
    public static ResponseEntity<ErrorResponse> error(CustomException e) {
        ErrorCode ec = e.getErrorCode();

        // cause까지 로그로 남김(내부 진단은 상세히 기록해도 OK)
        log.error("CustomException 발생: code={}, message={}, cause={}",
                ec.name(), ec.getMessage(), e.getCause(), e);

        if (ErrorExposurePolicy.isSensitive(ec)) {
            // 외부 노출은 통일된 더미 코드/메시지/상태로 마스킹
            PublicError pc = PublicError.REQUEST_DENIED;
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST) // 상태도 통일(유추 방지)
                    .body(ErrorResponse.builder()
                            .httpStatus(HttpStatus.BAD_REQUEST)
                            .code(pc.name())
                            .message(pc.getMessage())
                            .build());
        }

        // 비민감 오류의 코드·상태를 유지하고 공개 응답 메시지를 전달한다.
        return ResponseEntity
                .status(ec.getHttpStatus())
                .body(ErrorResponse.builder()
                        .httpStatus(ec.getHttpStatus())
                        .code(ec.name())
                        .message(e.getResponseMessage())
                        .build());
    }
}
