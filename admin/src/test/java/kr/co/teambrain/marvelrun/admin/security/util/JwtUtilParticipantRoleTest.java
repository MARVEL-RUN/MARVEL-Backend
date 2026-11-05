package kr.co.teambrain.marvelrun.admin.security.util;

import io.jsonwebtoken.Claims;
import kr.co.teambrain.marvelrun.admin.auth.command.application.exception.AuthErrorCode;
import kr.co.teambrain.marvelrun.admin.auth.command.application.exception.JwtAuthenticationException;
import kr.co.teambrain.marvelrun.admin.common.redis.RedisService;
import kr.co.teambrain.marvelrun.admin.security.details.CustomAdminDetail;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ParticipantTokenRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import static kr.co.teambrain.marvelrun.admin.security.util.TestTokenProperties.TOKEN_PROPERTIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 사용자 앱이 같은 키로 발급한 참가자 토큰을 관리자 앱이 인증에 쓰지 않는지 확인한다. */
@ExtendWith(MockitoExtension.class)
class JwtUtilParticipantRoleTest {

    @Mock
    RedisService redisService;

    JwtTokenProvider tokenProvider;
    JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        tokenProvider = new JwtTokenProvider(TOKEN_PROPERTIES);
        jwtUtil = new JwtUtil(TOKEN_PROPERTIES, redisService);
    }

    @Test
    void 참가자_토큰이면_인증을_만들지_않는다() {
        String token = tokenProvider.createAccessToken(
                new CustomAdminDetail("registration-1", "참가자", ParticipantTokenRole.PARTICIPANT.name()));
        Claims claims = jwtUtil.validateAccessToken(token);

        assertThatThrownBy(() -> jwtUtil.getAuthentication(claims))
                .isInstanceOf(JwtAuthenticationException.class)
                .extracting(e -> ((JwtAuthenticationException) e).getAuthErrorCode())
                .isEqualTo(AuthErrorCode.NOT_ADMIN_TOKEN);
    }

    @Test
    void 관리자_토큰은_그대로_인증한다() {
        for (String role : new String[]{"ADMIN", "총관리자"}) {
            String token = tokenProvider.createAccessToken(new CustomAdminDetail("admin-1", "관리자", role));
            Authentication authentication = jwtUtil.getAuthentication(jwtUtil.validateAccessToken(token));

            assertThat(authentication.getAuthorities())
                    .extracting(Object::toString)
                    .containsExactly("ROLE_" + role);
        }
    }

    @Test
    void 참가자_역할_이름은_사용자_앱과_같다() {
        assertThat(ParticipantTokenRole.includes("PARTICIPANT")).isTrue();
        assertThat(ParticipantTokenRole.includes("ADMIN")).isFalse();
        assertThat(ParticipantTokenRole.includes(null)).isFalse();
    }
}
