package kr.co.teambrain.marvelrun.user.participantauth.command.application.service;

import io.jsonwebtoken.Claims;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ParticipantTokenRole;
import kr.co.teambrain.marvelrun.user.common.exception.JwtAuthenticationException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.AuthErrorCode;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.redis.RedisService;
import kr.co.teambrain.marvelrun.user.common.security.config.TokenProperties;
import kr.co.teambrain.marvelrun.user.common.security.details.CustomAdminDetail;
import kr.co.teambrain.marvelrun.user.common.security.util.JwtTokenProvider;
import kr.co.teambrain.marvelrun.user.common.security.util.JwtUtil;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationAccessVerifier;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import kr.co.teambrain.marvelrun.user.participantauth.command.application.dto.ParticipantLoginResponse;
import kr.co.teambrain.marvelrun.user.participantauth.command.application.dto.ParticipantTokens;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 개인 본인확인(이름·생년월일·휴대폰·비밀번호)으로 참가자 토큰을 주고, 재발급·로그아웃을 처리한다.
 * 토큰의 subject는 신청 id, role은 PARTICIPANT다. 살아 있는 개인 신청만 로그인할 수 있다.
 */
@Service
@RequiredArgsConstructor
public class ParticipantAuthCommandService {

    /** 토큰 본문은 누구나 풀어 볼 수 있으므로 name 클레임에 실명 대신 고정값을 넣는다. */
    static final String TOKEN_NAME = "참가자";

    private static final String BEARER_PREFIX = "Bearer ";

    private final RegistrationCommandRepository registrationRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtUtil jwtUtil;
    private final RedisService redisService;
    private final TokenProperties tokenProperties;

    /**
     * 기존 개인 조회와 같은 값으로 신청을 찾는다. 실패 이유는 응답으로 구분하지 않는다.
     * 취소된 신청의 이력은 공개 조회로 본다.
     */
    @Transactional(readOnly = true)
    public ParticipantTokens login(String eventId, RegistrationAccessRequest access) {
        List<Registration> candidates = registrationRepository.findActivePersonalByIdentity(
                eventId, access.name(), access.phNum(), access.birth());

        Registration matched = null;
        for (Registration candidate : candidates) {
            try {
                RegistrationAccessVerifier.verifyPersonal(candidate, access, passwordEncoder);
                matched = candidate;
                break;
            } catch (CustomException e) {
                if (e.getErrorCode() != ErrorCode.REGISTRATION_ACCESS_DENIED) {
                    throw e;
                }
            }
        }
        if (matched == null) {
            throw new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED);
        }

        ParticipantTokens tokens = createTokens(matched);
        redisService.saveParticipantRefreshToken(
                matched.getId(), tokens.refreshToken(), refreshTtlMillis(), TimeUnit.MILLISECONDS);
        return tokens;
    }

    /** 참가자 refresh로 토큰 두 개를 새로 받는다. 삭제된 신청이 계속 재발급받지 않도록 신청을 한 번 본다. */
    @Transactional(readOnly = true)
    public ParticipantTokens refresh(String refreshHeader) {
        String refreshToken = extractToken(refreshHeader, AuthErrorCode.MISSING_REFRESH_HEADER);
        Claims claims = jwtUtil.validateRefreshToken(refreshToken);

        if (!isParticipant(claims)) {
            throw new JwtAuthenticationException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }

        String registrationId = claims.getSubject();
        if (registrationId == null
                || !redisService.isValidParticipantRefreshToken(registrationId, refreshToken)) {
            throw new JwtAuthenticationException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }

        Registration registration = registrationRepository.findById(registrationId)
                .filter(found -> !found.isSoftDeleted() && found.getOrganization() == null)
                .orElseThrow(() -> new JwtAuthenticationException(AuthErrorCode.INVALID_REFRESH_TOKEN));

        ParticipantTokens tokens = createTokens(registration);

        // 위 확인 뒤에 로그아웃이나 다른 재발급이 먼저 끝났으면 저장된 값이 달라져 교체가 실패한다.
        if (!redisService.replaceParticipantRefreshToken(
                registrationId, refreshToken, tokens.refreshToken(), refreshTtlMillis())) {
            throw new JwtAuthenticationException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }
        return tokens;
    }

    /**
     * refresh를 지우고 access를 남은 시간만큼 차단 목록에 올린다.
     * refresh 헤더는 받지 않는다. 프런트가 refresh를 잃어버려도 로그아웃할 수 있게 하기 위해서다.
     */
    public void logout(String accessHeader) {
        String accessToken = extractToken(accessHeader, AuthErrorCode.MISSING_AUTH_HEADER);
        Claims claims = jwtUtil.validateAccessToken(accessToken);

        if (!isParticipant(claims)) {
            throw new CustomException(ErrorCode.ACCESS_DENIED, " 참가자 토큰이 아닙니다.");
        }

        redisService.deleteParticipantRefreshToken(claims.getSubject());

        // 관리자 로그아웃(AdminCommandService)과 같이 남은 시간을 계산한다.
        long remainingMillis = Math.max(claims.getExpiration().getTime() - System.currentTimeMillis(), 0);
        if (remainingMillis > 0) {
            redisService.saveBlacklistAccessToken(accessToken, remainingMillis, TimeUnit.MILLISECONDS);
        }
    }

    private ParticipantTokens createTokens(Registration registration) {
        CustomAdminDetail participant = new CustomAdminDetail(
                registration.getId(), TOKEN_NAME, ParticipantTokenRole.PARTICIPANT.name());

        return new ParticipantTokens(
                jwtTokenProvider.createAccessToken(participant),
                jwtTokenProvider.createRefreshToken(participant),
                new ParticipantLoginResponse(
                        registration.getId(), registration.getEvent().getId(), registration.getName()));
    }

    private long refreshTtlMillis() {
        return tokenProperties.expirationTime().refreshToken().toMillis();
    }

    private static boolean isParticipant(Claims claims) {
        return ParticipantTokenRole.PARTICIPANT.name().equals(claims.get("role", String.class));
    }

    /** 관리자 AdminCommandService.extractToken과 같다. "Bearer " 접두어는 있어도 없어도 된다. */
    private static String extractToken(String header, AuthErrorCode missingHeaderError) {
        if (header == null || header.isBlank()) {
            throw new JwtAuthenticationException(missingHeaderError);
        }
        String value = header.trim();
        if (value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            value = value.substring(BEARER_PREFIX.length()).trim();
        }
        if (value.isEmpty()) {
            throw new JwtAuthenticationException(missingHeaderError);
        }
        return value;
    }
}
