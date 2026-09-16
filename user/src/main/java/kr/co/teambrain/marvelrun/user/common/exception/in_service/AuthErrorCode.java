package kr.co.teambrain.marvelrun.user.common.exception.in_service;

import lombok.Getter;
import org.springframework.http.HttpStatus;
@Getter
public enum AuthErrorCode {

    /*
     * Login
     */
    AUTHENTICATION_FAILED(
            HttpStatus.UNAUTHORIZED,
            "아이디 또는 비밀번호가 올바르지 않습니다.",
            false
    ),


    /*
     * Access Token
     */
    EXPIRED_ACCESS_TOKEN(
            HttpStatus.UNAUTHORIZED,
            "Access Token이 만료되었습니다.",
            true
    ),

    BLACKLISTED_ACCESS_TOKEN(
            HttpStatus.UNAUTHORIZED,
            "폐기된 Access Token입니다.",
            false
    ),


    /*
     * JWT
     */
    INVALID_SIGNATURE(
            HttpStatus.UNAUTHORIZED,
            "유효하지 않은 토큰 서명입니다.",
            false
    ),

    MALFORMED_TOKEN(
            HttpStatus.UNAUTHORIZED,
            "잘못된 형식의 토큰입니다.",
            false
    ),

    UNSUPPORTED_TOKEN(
            HttpStatus.UNAUTHORIZED,
            "지원하지 않는 토큰입니다.",
            false
    ),

    TOKEN_CLAIMS_EMPTY(
            HttpStatus.UNAUTHORIZED,
            "토큰 정보가 비어 있습니다.",
            false
    ),

    INVALID_TOKEN_TYPE(
            HttpStatus.UNAUTHORIZED,
            "잘못된 종류의 토큰입니다.",
            false
    ),

    TOKEN_VALIDATE_FAILED(
            HttpStatus.UNAUTHORIZED,
            "토큰 검증에 실패했습니다.",
            false
    ),


    /*
     * Refresh Token
     */
    INVALID_REFRESH_TOKEN(
            HttpStatus.UNAUTHORIZED,
            "유효하지 않은 Refresh Token입니다.",
            false
    ),

    EXPIRED_REFRESH_TOKEN(
            HttpStatus.UNAUTHORIZED,
            "Refresh Token이 만료되었습니다.",
            false
    ),


    /*
     * Header / Authentication
     */
    MISSING_AUTH_HEADER(
            HttpStatus.UNAUTHORIZED,
            "Authorization 헤더가 필요합니다.",
            false
    ),

    MISSING_REFRESH_HEADER(
            HttpStatus.UNAUTHORIZED,
            "Refresh Token이 필요합니다.",
            false
    ),

    UNAUTHORIZED(
            HttpStatus.UNAUTHORIZED,
            "인증이 필요합니다.",
            false
    ),

    INVALID_ISSUER(
            HttpStatus.UNAUTHORIZED,
            "유효하지 않은 토큰 발급자입니다.",
            false
    );


    private final HttpStatus httpStatus;
    private final String message;
    private final boolean canRefresh;


    AuthErrorCode(
            HttpStatus httpStatus,
            String message,
            boolean canRefresh
    ) {
        this.httpStatus = httpStatus;
        this.message = message;
        this.canRefresh = canRefresh;
    }
}