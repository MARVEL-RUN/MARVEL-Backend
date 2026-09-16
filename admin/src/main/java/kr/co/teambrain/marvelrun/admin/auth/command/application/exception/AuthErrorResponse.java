package kr.co.teambrain.marvelrun.admin.auth.command.application.exception;



import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.Map;

/* 명칭은 response이나 http response 목적이 아닌 filter단 검증 결과를 responseEntity와 유사하게 구성하여 전달하는 것이 목적이므로 exception에 위치 */
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


    public static AuthErrorResponse of(
            String path,
            AuthErrorCode code
    ) {

        return AuthErrorResponse.builder()
                .timestamp(
                        OffsetDateTime.now()
                                .toString()
                )
                .status(
                        code.getHttpStatus()
                                .value()
                )
                .error(
                        code.getHttpStatus()
                                .getReasonPhrase()
                )
                .code(
                        code
                )
                .message(
                        code.getMessage()
                )
                .canRefresh(
                        code.isCanRefresh()
                )
                .path(
                        path
                )
                .build();
    }
}