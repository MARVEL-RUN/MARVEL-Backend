package kr.co.teambrain.marvelrun.user.common.security.filter;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.co.teambrain.marvelrun.user.common.exception.JwtAuthenticationException;
import kr.co.teambrain.marvelrun.user.common.security.config.JwtAuthenticationEntryPoint;
import kr.co.teambrain.marvelrun.user.common.security.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@RequiredArgsConstructor
public class JwtFilter
        extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    private final JwtAuthenticationEntryPoint
            jwtAuthenticationEntryPoint;


    private static final AntPathMatcher pathMatcher =
            new AntPathMatcher();


    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String path =
                request.getRequestURI();


        /*
         * CORS Preflight
         */
        if ("OPTIONS".equalsIgnoreCase(
                request.getMethod()
        )) {

            filterChain.doFilter(
                    request,
                    response
            );

            return;
        }


        /*
         * JwtFilter 자체를 완전히 건너뛸 경로.
         *
         * public은 여기에 포함하지 않는다.
         */
        if (pathMatcher.match(
                "/actuator/health/**",
                path
        )
                || pathMatcher.match(
                "/health/**",
                path
        )
                || pathMatcher.match(
                "/swagger-ui/**",
                path
        )
                || pathMatcher.match(
                "/v3/api-docs/**",
                path
        )
                || pathMatcher.match(
                "/test-page/**",
                path
        )
                || path.equals(
                "/v1/admin/login"
        )
                || path.equals(
                "/marvelrun-flow-test.html"
        )
                || path.equals(
                "/v1/admin/refresh"
        )) {

            filterChain.doFilter(
                    request,
                    response
            );

            return;
        }


        String authorizationHeader =
                request.getHeader(
                        "Authorization"
                );


        /*
         * Public API
         *
         * 인증은 선택 사항.
         */
        if (pathMatcher.match(
                "/v1/public/**",
                path) ||
                pathMatcher.match(
                        "/public/**",
                        path)
        ) {


            authenticatePublicRequest(
                    authorizationHeader
            );


            filterChain.doFilter(
                    request,
                    response
            );

            return;
        }


        /*
         * 일반 인증 API
         */
        if (authorizationHeader != null
                && authorizationHeader.startsWith(
                "Bearer "
        )) {

            String accessToken =
                    authorizationHeader
                            .substring(7)
                            .trim();


            try {

                Claims claims =
                        jwtUtil.validateAccessToken(
                                accessToken
                        );


                Authentication authentication =
                        jwtUtil.getAuthentication(
                                claims
                        );


                SecurityContextHolder
                        .getContext()
                        .setAuthentication(
                                authentication
                        );

            } catch (JwtAuthenticationException e) {

                SecurityContextHolder
                        .clearContext();


                jwtAuthenticationEntryPoint
                        .commence(
                                request,
                                response,
                                e
                        );

                return;
            }
        }


        filterChain.doFilter(
                request,
                response
        );
    }


    private void authenticatePublicRequest(
            String authorizationHeader
    ) {

        /*
         * 비회원 요청.
         */
        if (authorizationHeader == null
                || !authorizationHeader.startsWith(
                "Bearer "
        )) {

            return;
        }


        String accessToken =
                authorizationHeader
                        .substring(7)
                        .trim();


        try {

            Claims claims =
                    jwtUtil.validateAccessToken(
                            accessToken
                    );


            Authentication authentication =
                    jwtUtil.getAuthentication(
                            claims
                    );


            SecurityContextHolder
                    .getContext()
                    .setAuthentication(
                            authentication
                    );

        } catch (JwtAuthenticationException e) {

            /*
             * Public API이므로 JWT 인증 실패 자체는
             * 요청 실패 사유가 아니다.
             *
             * 익명 요청으로 계속 진행.
             */
            SecurityContextHolder
                    .clearContext();
        }
    }
}