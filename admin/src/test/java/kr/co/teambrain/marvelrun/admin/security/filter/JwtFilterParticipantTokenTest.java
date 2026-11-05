package kr.co.teambrain.marvelrun.admin.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.teambrain.marvelrun.admin.common.redis.RedisService;
import kr.co.teambrain.marvelrun.admin.security.details.CustomAdminDetail;
import kr.co.teambrain.marvelrun.admin.security.util.JwtAuthenticationEntryPoint;
import kr.co.teambrain.marvelrun.admin.security.util.JwtTokenProvider;
import kr.co.teambrain.marvelrun.admin.security.util.JwtUtil;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ParticipantTokenRole;
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

import static kr.co.teambrain.marvelrun.admin.security.util.TestTokenProperties.TOKEN_PROPERTIES;
import static org.assertj.core.api.Assertions.assertThat;

/** 관리자 인증 API에 참가자 토큰을 보내면 필터에서 401로 끝나는지 확인한다. */
@ExtendWith(MockitoExtension.class)
class JwtFilterParticipantTokenTest {

    @Mock
    RedisService redisService;

    JwtTokenProvider tokenProvider;
    JwtFilter jwtFilter;

    @BeforeEach
    void setUp() {
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
    void 참가자_토큰이면_401이고_다음_필터를_부르지_않는다() throws Exception {
        String token = tokenProvider.createAccessToken(
                new CustomAdminDetail("registration-1", "참가자", ParticipantTokenRole.PARTICIPANT.name()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        jwtFilter.doFilter(request(token), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"NOT_ADMIN_TOKEN\"");
        assertThat(chain.getRequest()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void 관리자_토큰이면_인증을_설정하고_다음_필터를_부른다() throws Exception {
        String token = tokenProvider.createAccessToken(new CustomAdminDetail("admin-1", "관리자", "ADMIN"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        jwtFilter.doFilter(request(token), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("admin-1");
    }

    private static MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin-api/v1/admin/events/x");
        request.setContextPath("/admin-api");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
