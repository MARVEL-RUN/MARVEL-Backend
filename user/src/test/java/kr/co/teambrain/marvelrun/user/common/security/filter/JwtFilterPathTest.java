package kr.co.teambrain.marvelrun.user.common.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import kr.co.teambrain.marvelrun.user.common.redis.RedisService;
import kr.co.teambrain.marvelrun.user.common.security.config.JwtAuthenticationEntryPoint;
import kr.co.teambrain.marvelrun.user.common.security.details.CustomAdminDetail;
import kr.co.teambrain.marvelrun.user.common.security.util.JwtTokenProvider;
import kr.co.teambrain.marvelrun.user.common.security.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Date;
import java.util.Map;

import static kr.co.teambrain.marvelrun.user.common.security.util.TestTokenProperties.TOKEN_PROPERTIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** context-path(/api)가 붙은 요청에서도 공개 경로를 알아보는지 확인한다. */
@ExtendWith(MockitoExtension.class)
class JwtFilterPathTest {

    @Mock
    RedisService redisService;

    JwtTokenProvider tokenProvider;
    JwtFilter jwtFilter;

    @BeforeEach
    void setUp() {
        lenient().when(redisService.isBlacklisted(anyString())).thenReturn(false);
        tokenProvider = new JwtTokenProvider(TOKEN_PROPERTIES);
        jwtFilter = new JwtFilter(
                new JwtUtil(TOKEN_PROPERTIES, redisService),
                new JwtAuthenticationEntryPoint(new ObjectMapper()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 공개_경로는_망가진_토큰이어도_익명으로_통과한다() throws Exception {
        assertPublicPasses("not-a-jwt");
    }

    @Test
    void 공개_경로는_만료된_토큰이어도_익명으로_통과한다() throws Exception {
        assertPublicPasses(expiredAccessToken());
    }

    @Test
    void 공개_경로는_차단된_토큰이어도_익명으로_통과한다() throws Exception {
        String token = participantAccessToken();
        when(redisService.isBlacklisted(token)).thenReturn(true);
        assertPublicPasses(token);
    }

    @Test
    void 인증_경로에_만료된_토큰이면_401이다() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        jwtFilter.doFilter(request("/v1/participant/auth/logout", expiredAccessToken()), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"EXPIRED_ACCESS_TOKEN\"");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void 인증_경로에_유효한_참가자_토큰이면_인증을_설정한다() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        jwtFilter.doFilter(request("/v1/participant/auth/logout", participantAccessToken()),
                new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("registration-1");
    }

    private void assertPublicPasses(String token) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        jwtFilter.doFilter(request("/v1/public/events", token), response, chain);

        assertThat(response.getStatus()).isNotEqualTo(401);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private String participantAccessToken() {
        return tokenProvider.createAccessToken(new CustomAdminDetail("registration-1", "참가자", "PARTICIPANT"));
    }

    private static String expiredAccessToken() {
        return Jwts.builder()
                .setClaims(Map.of("name", "참가자", "role", "PARTICIPANT",
                        JwtTokenProvider.TOKEN_TYPE_CLAIM, JwtTokenProvider.ACCESS_TOKEN_TYPE))
                .setSubject("registration-1")
                .setIssuer(TOKEN_PROPERTIES.issuer())
                .setIssuedAt(new Date(System.currentTimeMillis() - 120_000))
                .setExpiration(new Date(System.currentTimeMillis() - 60_000))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(TOKEN_PROPERTIES.secret())),
                        SignatureAlgorithm.HS512)
                .compact();
    }

    private static MockHttpServletRequest request(String path, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api" + path);
        request.setContextPath("/api");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
