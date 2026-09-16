package kr.co.teambrain.marvelrun.admin.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import kr.co.teambrain.marvelrun.admin.auth.command.application.exception.AuthErrorCode;
import kr.co.teambrain.marvelrun.admin.auth.command.application.exception.AuthErrorResponse;
import kr.co.teambrain.marvelrun.admin.auth.command.application.exception.JwtAuthenticationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<String> handleAccessDeniedException(AccessDeniedException e) {
        // 로그를 남기거나 공통 응답 포맷으로 리턴
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body("해당 메뉴에 대한 접근 권한이 없습니다.");
    }

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ErrorResponse>
    handleCustomException(

            CustomException exception,

            HttpServletRequest request
    ) {

        ErrorCode errorCode =
                exception.getErrorCode();


        return ResponseEntity
                .status(
                        errorCode.getHttpStatus()
                )
                .body(
                        ErrorResponse.of(
                                request.getRequestURI(),
                                errorCode
                        )
                );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {

        log.error("Unhandled exception 발생:: type={}, message={}",
                e.getClass().getName(), e.getMessage(), e);


        ErrorResponse errorResponse = ErrorResponse.builder()
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .code("INTERNAL_SERVER_ERROR")
                .message(e.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }

    @ExceptionHandler(
            JwtAuthenticationException.class
    )
    public ResponseEntity<AuthErrorResponse>
    handleJwtAuthenticationException(

            JwtAuthenticationException exception,

            HttpServletRequest request
    ) {

        AuthErrorCode errorCode =
                exception.getAuthErrorCode();


        return ResponseEntity
                .status(
                        errorCode.getHttpStatus()
                )
                .body(
                        AuthErrorResponse.of(
                                request.getRequestURI(),
                                errorCode
                        )
                );
    }
}


