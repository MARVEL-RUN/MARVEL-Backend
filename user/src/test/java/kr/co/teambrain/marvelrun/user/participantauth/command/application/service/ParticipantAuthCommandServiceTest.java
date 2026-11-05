package kr.co.teambrain.marvelrun.user.participantauth.command.application.service;

import io.jsonwebtoken.Claims;
import kr.co.teambrain.marvelrun.user.common.exception.JwtAuthenticationException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.AuthErrorCode;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.redis.RedisService;
import kr.co.teambrain.marvelrun.user.common.security.details.CustomAdminDetail;
import kr.co.teambrain.marvelrun.user.common.security.util.JwtTokenProvider;
import kr.co.teambrain.marvelrun.user.common.security.util.JwtUtil;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import kr.co.teambrain.marvelrun.user.participantauth.command.application.dto.ParticipantTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static kr.co.teambrain.marvelrun.user.common.security.util.TestTokenProperties.TOKEN_PROPERTIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 개인 본인확인 로그인이 참가자 토큰을 주고, 재발급이 참가자 refresh만 받는지 본다. */
@ExtendWith(MockitoExtension.class)
class ParticipantAuthCommandServiceTest {

    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder(4);
    private static final RegistrationAccessRequest ACCESS =
            new RegistrationAccessRequest("홍길동", "1990-01-01", "010-1111-2222", "Test1234!");

    @Mock private RegistrationCommandRepository registrationRepository;
    @Mock private RedisService redisService;

    private final JwtTokenProvider tokenProvider = new JwtTokenProvider(TOKEN_PROPERTIES);

    private ParticipantAuthCommandService service() {
        return new ParticipantAuthCommandService(registrationRepository, ENCODER, tokenProvider,
                new JwtUtil(TOKEN_PROPERTIES, redisService), redisService, TOKEN_PROPERTIES);
    }

    /** 맞는 신청이 있으면 subject가 신청 id인 참가자 토큰을 주고 참가자 refresh 키에 저장한다. */
    @Test
    void loginIssuesParticipantTokens() {
        Registration registration = registration("registration-1", "Test1234!");
        when(registrationRepository.findActivePersonalByIdentity("event", "홍길동", "010-1111-2222", "1990-01-01"))
                .thenReturn(List.of(registration));

        ParticipantTokens tokens = service().login("event", ACCESS);

        Claims claims = new JwtUtil(TOKEN_PROPERTIES, redisService).validateRefreshToken(tokens.refreshToken());
        assertThat(claims.getSubject()).isEqualTo("registration-1");
        assertThat(claims.get("role", String.class)).isEqualTo("PARTICIPANT");
        assertThat(tokens.response().registrationId()).isEqualTo("registration-1");
        verify(redisService).saveParticipantRefreshToken(eq("registration-1"), eq(tokens.refreshToken()),
                anyLong(), eq(TimeUnit.MILLISECONDS));
    }

    /** 비밀번호가 틀리거나 살아 있는 신청이 없으면 같은 오류로 거절하고 토큰을 저장하지 않는다. */
    @Test
    void loginRejectsWrongPasswordOrMissingRegistration() {
        when(registrationRepository.findActivePersonalByIdentity("event", "홍길동", "010-1111-2222", "1990-01-01"))
                .thenReturn(List.of(registration("registration-1", "Other1234!")))
                .thenReturn(List.of());

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> service().login("event", ACCESS))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.REGISTRATION_ACCESS_DENIED);
        }
        verify(redisService, never()).saveParticipantRefreshToken(anyString(), anyString(), anyLong(), any());
    }

    /** 관리자 refresh 토큰으로는 참가자 토큰을 재발급하지 않는다. */
    @Test
    void refreshRejectsAdminToken() {
        String adminRefresh = tokenProvider.createRefreshToken(new CustomAdminDetail("admin-1", "관리자", "ADMIN"));

        assertThatThrownBy(() -> service().refresh("Bearer " + adminRefresh))
                .isInstanceOf(JwtAuthenticationException.class)
                .extracting(e -> ((JwtAuthenticationException) e).getAuthErrorCode())
                .isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN);
        verify(redisService, never()).isValidParticipantRefreshToken(anyString(), anyString());
    }

    /** 삭제된 신청은 저장된 refresh가 맞아도 재발급하지 않는다. */
    @Test
    void refreshRejectsDeletedRegistration() {
        String refresh = tokenProvider.createRefreshToken(new CustomAdminDetail("registration-1", "참가자", "PARTICIPANT"));
        when(redisService.isValidParticipantRefreshToken("registration-1", refresh)).thenReturn(true);
        when(registrationRepository.findById("registration-1"))
                .thenReturn(Optional.of(Registration.builder().id("registration-1").softDeleted(true).build()));

        assertThatThrownBy(() -> service().refresh(refresh))
                .isInstanceOf(JwtAuthenticationException.class);
        verify(redisService, never()).replaceParticipantRefreshToken(anyString(), anyString(), anyString(), anyLong());
    }

    private static Registration registration(String id, String rawPassword) {
        return Registration.builder().id(id).event(Event.builder().id("event").build())
                .name("홍길동").birth("1990-01-01").phNum("010-1111-2222")
                .password(ENCODER.encode(rawPassword)).build();
    }
}
