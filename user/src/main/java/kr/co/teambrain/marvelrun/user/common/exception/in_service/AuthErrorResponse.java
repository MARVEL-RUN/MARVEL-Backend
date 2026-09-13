package kr.co.teambrain.marvelrun.user.common.exception.in_service;


import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.Map;


@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuthErrorResponse {

    private final String timestamp;
    private final int status;
    private final String error;
    private final AuthErrorCode code;
    private final String message;
    private final boolean canRefresh;
    private final String path;
    private final Map<String, Object> meta;

    public static AuthErrorResponse of(String path, AuthErrorCode code) {
        return AuthErrorResponse.builder()
                .timestamp(OffsetDateTime.now().toString())
                .status(HttpStatus.UNAUTHORIZED.value())
                .error(HttpStatus.UNAUTHORIZED.getReasonPhrase())
                .code(code)
                .message(code.getMessage())
                .canRefresh(code.isCanRefresh())
                .path(path)
                .build();
    }
}