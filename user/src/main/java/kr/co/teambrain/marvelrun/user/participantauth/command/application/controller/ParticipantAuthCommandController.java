package kr.co.teambrain.marvelrun.user.participantauth.command.application.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.participantauth.command.application.dto.ParticipantLoginResponse;
import kr.co.teambrain.marvelrun.user.participantauth.command.application.dto.ParticipantTokens;
import kr.co.teambrain.marvelrun.user.participantauth.command.application.service.ParticipantAuthCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 참가자 로그인·재발급. 둘 다 공개 경로다. 토큰은 Authorization·refreshToken 응답 헤더로 준다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/public")
public class ParticipantAuthCommandController {

    static final String ACCESS_TOKEN_HEADER = HttpHeaders.AUTHORIZATION;
    static final String REFRESH_TOKEN_HEADER = "refreshToken";
    private static final String BEARER_PREFIX = "Bearer ";

    private final ParticipantAuthCommandService participantAuthCommandService;

    @Operation(summary = "참가자 로그인", description = "신청 조회와 같은 이름·생년월일·휴대폰·비밀번호로 로그인합니다.")
    @PostMapping("/events/{eventId}/participant-auth/login")
    public ResponseEntity<ParticipantLoginResponse> login(
            @PathVariable("eventId") String eventId,
            @Valid @RequestBody RegistrationAccessRequest request
    ) {
        return withTokens(participantAuthCommandService.login(eventId, request));
    }

    @Operation(summary = "참가자 토큰 재발급", description = "refreshToken 요청 헤더로 토큰 두 개를 새로 받습니다.")
    @PostMapping("/participant-auth/refresh")
    public ResponseEntity<ParticipantLoginResponse> refresh(
            @RequestHeader(value = REFRESH_TOKEN_HEADER, required = false) String refreshHeader
    ) {
        return withTokens(participantAuthCommandService.refresh(refreshHeader));
    }

    private static ResponseEntity<ParticipantLoginResponse> withTokens(ParticipantTokens tokens) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(ACCESS_TOKEN_HEADER, BEARER_PREFIX + tokens.accessToken());
        headers.add(REFRESH_TOKEN_HEADER, BEARER_PREFIX + tokens.refreshToken());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).headers(headers).body(tokens.response());
    }
}
