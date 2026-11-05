package kr.co.teambrain.marvelrun.user.participantauth.command.application.controller;

import io.swagger.v3.oas.annotations.Operation;
import kr.co.teambrain.marvelrun.user.participantauth.command.application.service.ParticipantAuthCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 로그인한 참가자의 세션 처리. 문자열은 ParticipantTokenRole.PARTICIPANT 이름과 같아야 한다(테스트로 확인). */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/participant/auth")
@PreAuthorize("hasRole('PARTICIPANT')")
public class ParticipantSessionCommandController {

    private final ParticipantAuthCommandService participantAuthCommandService;

    @Operation(summary = "참가자 로그아웃", description = "refresh를 지우고 access 토큰을 남은 시간만큼 쓰지 못하게 합니다.")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String accessHeader
    ) {
        participantAuthCommandService.logout(accessHeader);
        return ResponseEntity.noContent().build();
    }
}
