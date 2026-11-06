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
import kr.co.teambrain.marvelrun.user.event.command.application.domain.policy.EventRegistrationPolicy;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventRegistrationPolicyRepository;
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

/** 개인 본인확인 로그인이 주문자 키로 참가자 토큰을 주고, 재발급이 참가자 refresh만 받는지 본다. */
@ExtendWith(MockitoExtension.class)
class ParticipantAuthCommandServiceTest {

    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder(4);
    private static final RegistrationAccessRequest ACCESS =
            new RegistrationAccessRequest("홍길동", "1990-01-01", "010-1111-2222", "Test1234!");

    @Mock private RegistrationCommandRepository registrationRepository;
    @Mock private EventRegistrationPolicyRepository policyRepository;
    @Mock private RedisService redisService;

    private final JwtTokenProvider tokenProvider = new JwtTokenProvider(TOKEN_PROPERTIES);

    private ParticipantAuthCommandService service() {
        return new ParticipantAuthCommandService(registrationRepository, policyRepository, ENCODER, tokenProvider,
                new JwtUtil(TOKEN_PROPERTIES, redisService), redisService, TOKEN_PROPERTIES);
    }

    /** 맞는 주문이 있으면 subject가 주문자 키인 참가자 토큰을 주고, 그 키로 refresh를 저장하고 주문 수를 돌려준다. */
    @Test
    void loginIssuesOrdererTokens() {
        closedEvent();
        Registration first = registration("registration-1", "orderer-1", "Test1234!", false);
        Registration second = registration("registration-2", "orderer-1", "Test1234!", false);
        when(registrationRepository.findParticipantLoginCandidates("event", "홍길동", "010-1111-2222", "1990-01-01"))
                .thenReturn(List.of(first, second));
        when(registrationRepository.findOrdererRegistrations("orderer-1")).thenReturn(List.of(first, second));

        ParticipantTokens tokens = service().login("event", ACCESS);

        Claims claims = new JwtUtil(TOKEN_PROPERTIES, redisService).validateRefreshToken(tokens.refreshToken());
        assertThat(claims.getSubject()).isEqualTo("orderer-1");
        assertThat(claims.get("role", String.class)).isEqualTo("PARTICIPANT");
        assertThat(tokens.response().registrationId()).isEqualTo("registration-1");
        assertThat(tokens.response().orderCount()).isEqualTo(2);
        verify(redisService).saveParticipantRefreshToken(eq("orderer-1"), eq(tokens.refreshToken()),
                anyLong(), eq(TimeUnit.MILLISECONDS));
    }

    /** 비밀번호가 맞는 첫 후보의 주문자로 로그인한다. 모두 취소한 주문자도 로그인한다. */
    @Test
    void loginUsesFirstMatchingCandidateIncludingCanceledOrder() {
        closedEvent();
        Registration otherOrderer = registration("registration-1", "orderer-1", "Other1234!", false);
        Registration canceled = registration("registration-2", "orderer-2", "Test1234!", true);
        when(registrationRepository.findParticipantLoginCandidates("event", "홍길동", "010-1111-2222", "1990-01-01"))
                .thenReturn(List.of(otherOrderer, canceled));
        when(registrationRepository.findOrdererRegistrations("orderer-2")).thenReturn(List.of(canceled));

        ParticipantTokens tokens = service().login("event", ACCESS);

        assertThat(new JwtUtil(TOKEN_PROPERTIES, redisService).validateRefreshToken(tokens.refreshToken()).getSubject())
                .isEqualTo("orderer-2");
        assertThat(tokens.response().orderCount()).isEqualTo(1);
    }

    /** 비밀번호가 틀리거나 주문이 없으면 같은 오류로 거절하고 토큰을 저장하지 않는다. */
    @Test
    void loginRejectsWrongPasswordOrMissingRegistration() {
        closedEvent();
        when(registrationRepository.findParticipantLoginCandidates("event", "홍길동", "010-1111-2222", "1990-01-01"))
                .thenReturn(List.of(registration("registration-1", "orderer-1", "Other1234!", false)))
                .thenReturn(List.of());

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> service().login("event", ACCESS))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.REGISTRATION_ACCESS_DENIED);
        }
        verify(redisService, never()).saveParticipantRefreshToken(anyString(), anyString(), anyLong(), any());
    }

    /** 공개 조회·관리를 연 대회(오프라인)와 정책 행이 없는 대회는 후보를 찾기 전에 거절한다. */
    @Test
    void loginRejectsEventWithPublicAccess() {
        EventRegistrationPolicy open = mock(EventRegistrationPolicy.class);
        when(open.isPublicRegistrationAccessEnabled()).thenReturn(true);
        when(policyRepository.findByEventId("event")).thenReturn(Optional.of(open)).thenReturn(Optional.empty());

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> service().login("event", ACCESS))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PARTICIPANT_LOGIN_NOT_AVAILABLE);
        }
        verify(registrationRepository, never()).findParticipantLoginCandidates(any(), any(), any(), any());
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

    /** 주문자의 주문이 하나도 없으면 저장된 refresh가 맞아도 재발급하지 않는다. */
    @Test
    void refreshRejectsOrdererWithoutOrders() {
        String refresh = participantRefresh("orderer-1");
        when(redisService.isValidParticipantRefreshToken("orderer-1", refresh)).thenReturn(true);
        when(registrationRepository.findOrdererRegistrations("orderer-1")).thenReturn(List.of());

        assertThatThrownBy(() -> service().refresh(refresh))
                .isInstanceOf(JwtAuthenticationException.class);
        verify(redisService, never()).replaceParticipantRefreshToken(anyString(), anyString(), anyString(), anyLong());
    }

    /** 모두 취소한 주문자도 재발급받는다. 새 토큰도 주문자 키가 주체이고 같은 키로 refresh를 바꾼다. */
    @Test
    void refreshReissuesForCanceledOrderer() {
        closedEvent();
        String refresh = participantRefresh("orderer-1");
        when(redisService.isValidParticipantRefreshToken("orderer-1", refresh)).thenReturn(true);
        when(registrationRepository.findOrdererRegistrations("orderer-1"))
                .thenReturn(List.of(registration("registration-1", "orderer-1", "Test1234!", true)));
        when(redisService.replaceParticipantRefreshToken(eq("orderer-1"), eq(refresh), anyString(), anyLong()))
                .thenReturn(true);

        ParticipantTokens tokens = service().refresh(refresh);

        assertThat(new JwtUtil(TOKEN_PROPERTIES, redisService).validateRefreshToken(tokens.refreshToken()).getSubject())
                .isEqualTo("orderer-1");
        assertThat(tokens.response().orderCount()).isEqualTo(1);
    }

    /** 대회가 다시 공개 조회·관리로 열리면 재발급도 거절한다. */
    @Test
    void refreshRejectsEventWithPublicAccess() {
        String refresh = participantRefresh("orderer-1");
        when(redisService.isValidParticipantRefreshToken("orderer-1", refresh)).thenReturn(true);
        when(registrationRepository.findOrdererRegistrations("orderer-1"))
                .thenReturn(List.of(registration("registration-1", "orderer-1", "Test1234!", false)));
        when(policyRepository.findByEventId("event")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().refresh(refresh))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARTICIPANT_LOGIN_NOT_AVAILABLE);
        verify(redisService, never()).replaceParticipantRefreshToken(anyString(), anyString(), anyString(), anyLong());
    }

    private void closedEvent() {
        EventRegistrationPolicy closed = mock(EventRegistrationPolicy.class);
        when(closed.isPublicRegistrationAccessEnabled()).thenReturn(false);
        when(policyRepository.findByEventId("event")).thenReturn(Optional.of(closed));
    }

    private String participantRefresh(String ordererKey) {
        return tokenProvider.createRefreshToken(new CustomAdminDetail(ordererKey, "참가자", "PARTICIPANT"));
    }

    private static Registration registration(String id, String ordererKey, String rawPassword, boolean canceled) {
        return Registration.builder().id(id).ordererKey(ordererKey).event(Event.builder().id("event").build())
                .name("홍길동").birth("1990-01-01").phNum("010-1111-2222").softDeleted(canceled)
                .password(ENCODER.encode(rawPassword)).build();
    }
}
