package kr.co.teambrain.marvelrun.user.common.security.config;


import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.channel.ChannelProcessingFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
@EnableWebSecurity // 스프링 시큐리티 필터가 스프링 필터체인에 등록된다.
@RequiredArgsConstructor
public class SecurityConfig {
//    private final LoginSuccessHandler loginSuccessHandler;
//
//    private final JwtFilter jwtFilter;

    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    private static final String[] SWAGGER = {
            "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/actuator/health/**", "/actuator/health"
    };

    /** 체인#1: API + Swagger (JWT, 401, 세션X, 폼로그인 미사용) */
    @Bean @Order(0)
    SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                .csrf(csrf -> csrf.disable()) // REST API면 비활성
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(SWAGGER).permitAll()
                        .requestMatchers("/api/auth/**").permitAll() // 로그인/리프레시 등 공개
                        .requestMatchers("/api/v1/notification/global").permitAll()
                        .anyRequest().authenticated()
                )
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        (req, res, ex) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED) // 302 대신 401
                ))
                .formLogin(AbstractHttpConfigurer::disable) // 이 체인에서는 폼로그인 쓰지 않음
                .httpBasic(AbstractHttpConfigurer::disable);

        return http.build();
    }

    /** 체인#2: 웹 로그인(폼로그인 유지) */
    @Bean
    @Order(1)
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        CorsFilter corsFilter = new CorsFilter(corsConfigurationSource());

        http.csrf(CsrfConfigurer::disable)
                .securityMatcher("/api/**");                    // ✅ 패턴 단일화


        http.addFilterBefore(corsFilter, ChannelProcessingFilter.class);
//        http.cors(cors -> cors
//                .configurationSource(corsConfigurationSource()));

        http.authorizeHttpRequests(authorize -> authorize
                // ✅ 1) OPTIONS 최우선 허용 (preflight)
                .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()

                // ✅ 2) public 먼저
                .requestMatchers(
                                "/toss-test.html",
                                "/public/**",
                                "/internal/**",
                                "/auth/**"
                        ).permitAll()
                .requestMatchers("/login", "/logout").permitAll()
                .requestMatchers("/api/v1/notification/global").permitAll()

                // ✅ 3) 그 다음에 인증 요구 (중요: OPTIONS보다 뒤에 오게)
                .requestMatchers("/api/*/**").authenticated()
                .anyRequest().authenticated()
        )

                // 세션 사용을 완벽히 차단하기 위한 목적
                // HTTP 요청이 끝난 뒤 해당 요청에 사용된 사용자의 SecurityContext를 HttpSession 같은 서버 상태 저장소에 보관하지 않겠다
                .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) // 세션을 사용하지 않겠다는 '정책'
                .securityContext(sc -> sc
                        .securityContextRepository(new NullSecurityContextRepository()) // 세션을 저장하는 물리 저장소를 아예 제거함.
                )
                .formLogin(f -> f.disable())

                // 로그인 설정
//                .formLogin(form -> form
//                        //  .loginPage("/loginPage") // 로그인 진행 페이지 url. 지금은 로그인 페이지 없으므로 Security 자체 페이지 기반 테스트위해 비활성화
//                        .loginProcessingUrl("/login") // Security가 낚아챌 로그인 시도 url
//                        .failureUrl("/loginFail")
//                        .defaultSuccessUrl("/") // 로그인 성공 시 페이지
//                        .usernameParameter("account") // 로그인 폼 또는 json으로 받아올 명칭 설정
//                        .passwordParameter("account_password") // 로그인 폼 또는 json으로 받아올 명칭 설정
//                        .successHandler(loginSuccessHandler) // 람다대신 클래스로 관리
//                        // .failureHandler() // 추후 작성. 실패시 처리(아이디 없음, 비밀번호 틀림 등 필요한 경우에 맞추어 기입)
//                        .permitAll()
//                )

                    // 로그아웃은 나중에..
//                .logout((auth) -> auth
//                        .logoutUrl("/logout")
//                )

                // 인증안된 302로 인해 시큐리티가 로그인 페이지로 강제이동 시키는 경우 방지 -> 401 에러 반환
                // 403
                .exceptionHandling(e -> e.authenticationEntryPoint(jwtAuthenticationEntryPoint))
                .httpBasic(AbstractHttpConfigurer::disable);

                // 요청 시 jwt 필터를 이용해 검증


        return http.build();
    }
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() { // BCrypt: 단방향 해시
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UrlBasedCorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.addAllowedOriginPattern("https://marathontest2026.duckdns.org"); // nginx 도메인
        config.addAllowedOrigin("http://localhost:3000"); // 허용할 도메인
        config.addAllowedOriginPattern("http://192.168.0.*:3000");
        config.addAllowedOrigin("https://marvelrunkorea2026.com");
        config.addAllowedOriginPattern("https://*.marvelrunkorea2026.com");
        config.addAllowedHeader("*"); // 모든 헤더 허용
        config.addAllowedMethod("*"); // 모든 HTTP 메소드 허용
        config.addExposedHeader("Authorization");      // Auth 헤더 허용
        config.addExposedHeader("refreshToken");      // Refresh 헤더 허용

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
