package kr.co.teambrain.marvelrun.admin.security.config;

import kr.co.teambrain.marvelrun.admin.security.filter.JwtFilter;
import kr.co.teambrain.marvelrun.admin.security.util.JwtAuthenticationEntryPoint;
import kr.co.teambrain.marvelrun.admin.security.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtUtil jwtUtil;

    private static final String[] SWAGGER = {
            "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/actuator/health/**", "/actuator/health"
    };

    private final JwtAuthenticationEntryPoint
            jwtAuthenticationEntryPoint;


    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http
    ) throws Exception {

        http
                .csrf(
                        csrf ->
                                csrf.disable()
                )

                .authorizeHttpRequests(
                        authorize ->
                                authorize
                                        .requestMatchers(
                                                "/health",
                                                "/actuator/health",
                                                "/actuator/health/**",
                                                "/swagger-ui/**",
                                                "/v3/api-docs/**",
                                                "/v1/public/**",
                                                "/v1/public/admin/login",
                                                "/v1/public/admin/refresh"
                                        )
                                        .permitAll()

                                        .anyRequest()
                                        .authenticated()
                )

                .sessionManagement(
                        session ->
                                session.sessionCreationPolicy(
                                        SessionCreationPolicy.STATELESS
                                )
                )

                .cors(
                        cors ->
                                cors.configurationSource(
                                        corsConfigurationSource()
                                )
                )

                .exceptionHandling(
                        exception ->
                                exception.authenticationEntryPoint(
                                        jwtAuthenticationEntryPoint
                                )
                );


        http.addFilterBefore(
                new JwtFilter(
                        jwtUtil,
                        jwtAuthenticationEntryPoint
                ),
                UsernamePasswordAuthenticationFilter.class
        );


        return http.build();
    }


    @Bean
    public PasswordEncoder passwordEncoder() {

        return new BCryptPasswordEncoder();
    }


    @Bean
    public UrlBasedCorsConfigurationSource
    corsConfigurationSource() {

        CorsConfiguration config =
                new CorsConfiguration();


        /*
         * Cookie 기반 인증은 사용하지 않지만,
         * Authorization / refreshToken 등 인증 관련 헤더를
         * 포함한 credential 요청을 허용하기 위해 유지.
         */
        config.setAllowCredentials(
                true
        );


        /*
         * MarvelRun Admin Frontend Origin
         *
         * localhost:
         *   로컬 프론트 개발 환경
         *
         * marathontest2026.duckdns.org:
         *   AWS 테스트 환경
         *
         * marvelrunkorea2026.com:
         *   실제 운영 환경
         */
        config.setAllowedOrigins(
                List.of(
                        "http://localhost:3000",
                        "https://marathontest2026.duckdns.org",
                        "https://marvelrunkorea2026.com"
                )
        );


        config.addAllowedHeader(
                "*"
        );

        config.addAllowedMethod(
                "*"
        );


        /*
         * 브라우저 Javascript에서 읽어야 하는 응답 헤더.
         *
         * Authorization
         *   새 Access Token
         *
         * refreshToken
         *   새 Refresh Token
         *
         * Content-Disposition
         *   Excel/PDF 등의 파일 다운로드 시 사용
         */
        config.setExposedHeaders(
                List.of(
                        "Authorization",
                        "refreshToken",
                        "Content-Disposition"
                )
        );


        /*
         * Preflight 결과 캐시.
         * 1시간 동안 동일 CORS preflight 요청을 재사용.
         */
        config.setMaxAge(
                3600L
        );


        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();


        source.registerCorsConfiguration(
                "/**",
                config
        );


        return source;
    }
}