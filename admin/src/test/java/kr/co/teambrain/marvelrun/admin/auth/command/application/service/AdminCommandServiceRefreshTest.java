package kr.co.teambrain.marvelrun.admin.auth.command.application.service;

import kr.co.teambrain.marvelrun.admin.auth.command.application.dto.AdminLoginSuccess;
import kr.co.teambrain.marvelrun.admin.auth.command.application.exception.AuthErrorCode;
import kr.co.teambrain.marvelrun.admin.auth.command.application.exception.JwtAuthenticationException;
import kr.co.teambrain.marvelrun.admin.auth.command.repository.AdminCommandRepository;
import kr.co.teambrain.marvelrun.admin.common.redis.RedisService;
import kr.co.teambrain.marvelrun.admin.security.details.CustomAdminDetail;
import kr.co.teambrain.marvelrun.admin.security.util.JwtTokenProvider;
import kr.co.teambrain.marvelrun.admin.security.util.JwtUtil;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ParticipantTokenRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.concurrent.TimeUnit;

import static kr.co.teambrain.marvelrun.admin.security.util.TestTokenProperties.TOKEN_PROPERTIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 관리자 재발급이 참가자 refresh 토큰을 Redis 조회 전에 거부하는지 확인한다. */
@ExtendWith(MockitoExtension.class)
class AdminCommandServiceRefreshTest {

    @Mock
    AdminCommandRepository adminCommandRepository;
    @Mock
    PasswordEncoder passwordEncoder;
    @Mock
    RedisService redisService;

    JwtTokenProvider tokenProvider;
    AdminCommandService service;

    @BeforeEach
    void setUp() {
        tokenProvider = new JwtTokenProvider(TOKEN_PROPERTIES);
        service = new AdminCommandService(
                adminCommandRepository,
                passwordEncoder,
                tokenProvider,
                new JwtUtil(TOKEN_PROPERTIES, redisService),
                redisService,
                TOKEN_PROPERTIES);
    }

    @Test
    void 참가자_refresh_토큰은_Redis를_보지_않고_거부한다() {
        String refresh = tokenProvider.createRefreshToken(
                new CustomAdminDetail("registration-1", "참가자", ParticipantTokenRole.PARTICIPANT.name()));

        assertThatThrownBy(() -> service.refresh("Bearer " + refresh))
                .isInstanceOf(JwtAuthenticationException.class)
                .extracting(e -> ((JwtAuthenticationException) e).getAuthErrorCode())
                .isEqualTo(AuthErrorCode.NOT_ADMIN_TOKEN);
        verifyNoInteractions(redisService);
    }

    @Test
    void 관리자_refresh_토큰은_새_토큰_두_개를_받는다() {
        String refresh = tokenProvider.createRefreshToken(new CustomAdminDetail("admin-1", "관리자", "ADMIN"));
        when(redisService.isValidRefreshToken("admin-1", refresh)).thenReturn(true);

        AdminLoginSuccess result = service.refresh("Bearer " + refresh);

        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.refreshToken()).isNotBlank();
        verify(redisService).saveWhitelistRefreshToken(
                eq("admin-1"), eq(result.refreshToken()), anyLong(), eq(TimeUnit.MILLISECONDS));
    }
}
