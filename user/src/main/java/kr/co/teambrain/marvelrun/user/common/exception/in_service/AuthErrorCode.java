package kr.co.teambrain.marvelrun.user.common.exception.in_service;

import lombok.Getter;


@Getter
public enum AuthErrorCode {
    EXPIRED_ACCESS_TOKEN("Access token expired", true),
    INVALID_SIGNATURE("Invalid token signature", false),
    MALFORMED_TOKEN("Malformed token", false),
    UNSUPPORTED_TOKEN("Unsupported token", false),
    INVALID_TOKEN("Invalid token", false),
    BLACKLISTED_TOKEN("Blacklisted token", false),
    NOT_FOUND_TOKEN("Token not found", false),
    TOKEN_VALIDATE_FAILED("Token validation failed", false),
    UNAUTHORIZED("Unauthorized", false);

    private final String message;
    private final boolean canRefresh;

    AuthErrorCode(String message, boolean canRefresh) {
        this.message = message;
        this.canRefresh = canRefresh;
    }
}