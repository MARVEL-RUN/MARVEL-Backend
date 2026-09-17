package kr.co.teambrain.marvelrun.admin.common.exception;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record ErrorResponse(

        int status,

        String code,

        String message,

        String path,

        LocalDateTime timestamp

) {

    public static ErrorResponse of(
            String path,
            ErrorCode errorCode
    ) {

        return new ErrorResponse(
                errorCode
                        .getHttpStatus()
                        .value(),

                errorCode.name(),

                errorCode.getMessage(),

                path,

                LocalDateTime.now()
        );
    }
}