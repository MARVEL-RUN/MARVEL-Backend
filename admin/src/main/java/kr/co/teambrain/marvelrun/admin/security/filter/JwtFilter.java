package kr.co.teambrain.marvelrun.admin.security.filter;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.co.teambrain.marvelrun.admin.auth.command.application.exception.JwtAuthenticationException;
import kr.co.teambrain.marvelrun.admin.security.util.JwtAuthenticationEntryPoint;
import kr.co.teambrain.marvelrun.admin.security.util.JwtUtil;
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


    private static final AntPathMatcher PATH_MATCHER =
            new AntPathMatcher();


    @Override
    protected void doFilterInternal(

            HttpServletRequest request,

            HttpServletResponse response,

            FilterChain filterChain

    ) throws ServletException, IOException {

        String path =
                request.getRequestURI();


        if (isWhitelistPath(
                path
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


                jwtAuthenticationEntryPoint.commence(
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


    private boolean isWhitelistPath(
            String path
    ) {

        return PATH_MATCHER.match(
                "/actuator/health/**",
                path
        )
                || PATH_MATCHER.match(
                "/health/**",
                path
        )
                || PATH_MATCHER.match(
                "/swagger-ui/**",
                path
        )
                || PATH_MATCHER.match(
                "/v3/api-docs/**",
                path
        )
                || path.equals(
                "/v1/admin/login"
        )
                || path.equals(
                "/v1/admin/refresh"
        );
    }
}