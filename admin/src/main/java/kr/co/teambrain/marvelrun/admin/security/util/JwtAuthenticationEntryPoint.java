package kr.co.teambrain.marvelrun.admin.security.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.co.teambrain.marvelrun.admin.auth.command.application.exception.AuthErrorCode;
import kr.co.teambrain.marvelrun.admin.auth.command.application.exception.AuthErrorResponse;
import kr.co.teambrain.marvelrun.admin.auth.command.application.exception.JwtAuthenticationException;


import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint
        implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;


    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {

        AuthErrorCode authErrorCode =
                authException
                        instanceof JwtAuthenticationException jwtException
                        ? jwtException.getAuthErrorCode()
                        : AuthErrorCode.UNAUTHORIZED;


        response.setStatus(
                authErrorCode
                        .getHttpStatus()
                        .value()
        );

        response.setContentType(
                MediaType.APPLICATION_JSON_VALUE
        );

        response.setCharacterEncoding(
                "UTF-8"
        );


        objectMapper.writeValue(
                response.getWriter(),

                AuthErrorResponse.of(
                        request.getRequestURI(),
                        authErrorCode
                )
        );
    }
}