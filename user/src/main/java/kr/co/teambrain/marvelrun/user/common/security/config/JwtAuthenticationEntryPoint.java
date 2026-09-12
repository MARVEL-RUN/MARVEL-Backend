package kr.co.teambrain.marvelrun.user.common.security.config;


import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.teambrain.marvelrun.user.common.exception.JwtAuthenticationException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.AuthErrorCode;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.AuthErrorResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** JwtFilter에서 사전에 반려되어 발생한 401 에러에 대한 HTTP 기반 반환 동작 처리
 * JwtFilter는 DispatcherServlet의 앞단에 위치하므로
 * */

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException, ServletException {

        AuthErrorCode code = AuthErrorCode.UNAUTHORIZED;

        if (authException instanceof JwtAuthenticationException jwtEx) {
            code = jwtEx.getAuthErrorCode();
        }

        AuthErrorResponse body = AuthErrorResponse.of(request.getRequestURI(), code);

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        objectMapper.writeValue(response.getWriter(), body);
    }
}