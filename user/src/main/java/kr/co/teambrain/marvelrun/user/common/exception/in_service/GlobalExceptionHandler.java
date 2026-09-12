package kr.co.teambrain.marvelrun.user.common.exception.in_service;

import kr.co.teambrain.marvelrun.user.common.exception.BatchValidationException;
import kr.co.teambrain.marvelrun.user.common.exception.out_service.MemberError;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ErrorResponse> handleCustomException(CustomException e) {

        return ErrorResponse.error(e);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {

        log.error("Unhandled exception 발생:: type={}, message={}",
                e.getClass().getName(), e.getMessage(), e);


        ErrorResponse errorResponse = ErrorResponse.builder()
                .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .code("INTERNAL_SERVER_ERROR")
                .message(e.getMessage())
                .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }

    // ✅ [추가] 배치 검증 실패 처리
    @ExceptionHandler(BatchValidationException.class)
    public ResponseEntity<ErrorResponse> handleBatchValidation(BatchValidationException e) {
        log.warn("Batch validation failed: {} errors",
                e.getErrors() == null ? 0 : e.getErrors().size());

        ErrorResponse response = ErrorResponse.builder()
                .httpStatus(HttpStatus.BAD_REQUEST)
                .code("BATCH_VALIDATION_FAILED")
                .message("배치 유효성 검증에 실패했습니다.")
                .errors(e.getErrors())                // 여기서 새 필드 사용
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

//    @ExceptionHandler(OtpException.class)
//    public ResponseEntity<ErrorResponse> handle(OtpException e) {
//        // 요구사항: '코드 틀림/타인 코드'는 동일하게 NOT_FOUND로 처리
//        // 상태코드는 서비스 정책에 맞춰 조정 가능(예: 400/404 등)
//        HttpStatus status = switch (e.getReason()) {
//            case EXPIRED     -> HttpStatus.GONE;      // 410 Gone (권장)
//            case NOT_FOUND   -> HttpStatus.NOT_FOUND; // 404
//            case ALREADY_ISSUED, MAX_REQUESTED -> HttpStatus.CONFLICT; // 409
//        };
//
//        ErrorResponse response = ErrorResponse.builder()
//                .httpStatus(status)
//                .code(e.getReason().name())
//                .message(e.getMessage())
//                .build();
//        return ResponseEntity.status(status).body(response);
//    }

    /** DB 세이브시 관련 에러를 래핑 처리.
     * 이때 래핑이란
     * 1. 아예 프론트에 개발 관련 정보 안들어가게 막기
     * 2. 전달되어야 하는 unique 관련 정보는 어느정도 내용을 통제하여 제공하기
     * */
//    @ExceptionHandler(DataIntegrityViolationException.class)
//    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
//        // root cause 메시지 추출
//        String rootMsg = Optional.ofNullable(ex.getRootCause())
//                .map(Throwable::getMessage)
//                .orElse("");
//
//        log.warn("DataIntegrityViolationException: {}", rootMsg);
//
//        // 기본값: 일반적인 DB 무결성 에러.
//        // DB 관련 에러를 전부 래핑 처리해버린다.
//        ErrorCode code = ErrorCode.DATABASE_ERROR;
//
//        // DB 관련 에러지만, 관련 힌트는 제공해야 하는 경우
//        if (rootMsg.contains(DBUniqueCode.uq_user_unique_info.name())) {
//            code = ErrorCode.ALREADY_REGISTER_USER;
//        }
//        else if (rootMsg.contains(DBUniqueCode.uq_user_account.name())) {
//            code = ErrorCode.DUPLICATE_ACCOUNT_ID;
//        }
//
//        ErrorResponse body = new ErrorResponse(code);
//
//        // 유니크 충돌은 보통 409 Conflict를 많이 사용
//        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
//    }

    /** custom validator나 기타 valid에 의해 불만족 요소가 확인된 기입 값에 대한 예외를 '한번에' 반환. valid 검증에 대한 배치 예외 처리로 인지하면 됨 */
    /** custom validator나 기타 valid에 의해 불만족 요소가 확인된 기입 값에 대한 예외를 한번에 반환 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        BindingResult bindingResult = e.getBindingResult();

        List<MemberError> errors = new ArrayList<>();

        for (FieldError fe : bindingResult.getFieldErrors()) {
            errors.add(MemberError.builder()
                    .row(null)
                    .field("REQUEST_BODY")
                    .target(fe.getField())
                    .code(resolveValidationCode(fe))
                    .message(fe.getDefaultMessage())
                    .rejectedValue(fe.getRejectedValue())
                    .build());
        }

        for (ObjectError oe : bindingResult.getGlobalErrors()) {
            errors.add(MemberError.builder()
                    .row(null)
                    .field("REQUEST_BODY")
                    .target(null)
                    .code(resolveValidationCode(oe))
                    .message(oe.getDefaultMessage())
                    .rejectedValue(null)
                    .build());
        }

        ErrorResponse response = ErrorResponse.builder()
                .httpStatus(HttpStatus.BAD_REQUEST)
                .code("INVALID_INPUT_VALUE")
                .message("입력값 검증에 실패했습니다.")
                .errors(errors)
                .build();

        return ResponseEntity.badRequest().body(response);
    }

    /** 서버에서 채용하지 않은 날짜 구조로 인해 정상적으로 동작하지 않는 경우를 핸들링해 responsebody 구축 */
//    @ExceptionHandler(HttpMessageNotReadableException.class)
//    public ResponseEntity<?> handleNotReadable(HttpMessageNotReadableException ex) {
//
//        // 1) 체인에서 InvalidFormatException을 찾는다 (rootCause 쓰지 마라)
//        com.fasterxml.jackson.databind.exc.InvalidFormatException ife =
//                findCause(ex, com.fasterxml.jackson.databind.exc.InvalidFormatException.class);
//
//        if (ife != null) {
//            String jacksonPath = toJacksonPath(ife.getPath());
//
//            String msg = "요청 값 형식이 올바르지 않습니다.";
//            // LocalDate 판정은 targetType이 null일 수 있어 원문 메시지도 같이 본다
//            boolean isLocalDate =
//                    (ife.getTargetType() != null && java.time.LocalDate.class.isAssignableFrom(ife.getTargetType()))
//                            || (ife.getOriginalMessage() != null && ife.getOriginalMessage().contains("java.time.LocalDate"));
//
//            if (isLocalDate) {
//                msg = "날짜 형식이 올바르지 않습니다. (예: 2025-02-28)";
//            }
//
//            MemberError me = JsonParseMemberErrorMapper.fromJacksonPath(jacksonPath, msg);
//
//            // 원하면 잘못 들어온 값을 rejectedValue로 내려줄 수 있음
//            // (비밀번호 같은 민감 값은 절대 포함 금지)
//            // me = me.toBuilder().rejectedValue(ife.getValue()).build();
//
//            return ResponseEntity.badRequest().body(batchErrorResponse(List.of(me)));
//        }
//
//        // 2) InvalidFormatException이 아니더라도 JsonMappingException이면 path는 얻을 수 있음
//        com.fasterxml.jackson.databind.JsonMappingException jme =
//                findCause(ex, com.fasterxml.jackson.databind.JsonMappingException.class);
//
//        if (jme != null) {
//            String jacksonPath = toJacksonPath(jme.getPath());
//            MemberError me = JsonParseMemberErrorMapper.fromJacksonPath(jacksonPath, "요청 값 형식이 올바르지 않습니다.");
//            return ResponseEntity.badRequest().body(batchErrorResponse(List.of(me)));
//        }
//
//        // 3) 진짜 JSON 문법 자체가 깨진 경우(path 없음)만 디폴트 처리
//        MemberError me = MemberError.builder()
//                .row(null)
//                .field(BatchTargetField.REGI_LIST.name())
//                .target(null)
//                .code("INVALID_JSON")
//                .message("요청 본문(JSON) 형식이 올바르지 않습니다.")
//                .rejectedValue(null)
//                .build();
//
//        return ResponseEntity.badRequest().body(batchErrorResponse(List.of(me)));
//    }

    private static <T extends Throwable> T findCause(Throwable t, Class<T> type) {
        Throwable cur = t;
        while (cur != null) {
            if (type.isInstance(cur)) return type.cast(cur);
            cur = cur.getCause();
        }
        return null;
    }

    private static String toJacksonPath(List<com.fasterxml.jackson.databind.JsonMappingException.Reference> refs) {
        if (refs == null || refs.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        for (com.fasterxml.jackson.databind.JsonMappingException.Reference ref : refs) {
            if (ref.getFieldName() != null) {
                if (sb.length() > 0) sb.append('.');
                sb.append(ref.getFieldName());
            } else {
                sb.append('[').append(ref.getIndex()).append(']');
            }
        }
        return sb.toString();
    }


    private Object batchErrorResponse(List<MemberError> errors) {
        // 당신이 이미 쓰는 BatchValidationException 응답 포맷과 동일하게 구성
        // 예: { httpStatus, code, message, errors }
        return new ErrorResponse(
                HttpStatus.BAD_REQUEST,
                "BATCH_VALIDATION_FAILED",
                "배치 유효성 검증에 실패했습니다.",
                errors
        );
    }

    private String resolveValidationCode(FieldError fe) {
        if (fe.getCode() == null) {
            return "INVALID_INPUT_VALUE";
        }

        return switch (fe.getCode()) {
            case "NotNull", "NotBlank", "NotEmpty" -> "REQUIRED";
            case "Pattern", "Email", "Size", "Past", "Future", "Positive", "PositiveOrZero",
                    "Negative", "NegativeOrZero", "Min", "Max", "DecimalMin", "DecimalMax" -> "INVALID_FORMAT";
            case "ValidCashReceiptIdentifier" -> "INVALID_FORMAT";
            default -> "INVALID_INPUT_VALUE";
        };
    }

    private String resolveValidationCode(ObjectError oe) {
        if (oe.getCode() == null) {
            return "INVALID_INPUT_VALUE";
        }

        return switch (oe.getCode()) {
            case "ValidCashReceiptIdentifier" -> "INVALID_INPUT_VALUE";
            default -> "INVALID_INPUT_VALUE";
        };
    }
}
