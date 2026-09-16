package kr.co.teambrain.marvelrun.admin.security.util;

import kr.co.teambrain.marvelrun.admin.auth.command.application.exception.AuthErrorCode;
import kr.co.teambrain.marvelrun.admin.auth.command.application.exception.JwtAuthenticationException;
import kr.co.teambrain.marvelrun.admin.security.details.CustomAdminDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class AdminInfoUtil {

    private AdminInfoUtil() {
    }


    private static CustomAdminDetail
    getAuthentication() {

        Authentication authentication =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication();


        if (authentication == null
                || !(authentication.getPrincipal()
                instanceof CustomAdminDetail detail)) {

            throw new JwtAuthenticationException(
                    AuthErrorCode.UNAUTHORIZED
            );
        }


        return detail;
    }


    public static String getAdminId() {

        return getAuthentication()
                .getUsername();
    }


    public static String getAdminName() {

        return getAuthentication()
                .getName();
    }
}