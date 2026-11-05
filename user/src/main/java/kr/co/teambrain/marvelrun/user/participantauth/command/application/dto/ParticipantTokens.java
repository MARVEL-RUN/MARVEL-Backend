package kr.co.teambrain.marvelrun.user.participantauth.command.application.dto;

/** 서비스가 컨트롤러에 넘기는 토큰 두 개와 응답 본문. */
public record ParticipantTokens(
        String accessToken,
        String refreshToken,
        ParticipantLoginResponse response
) {
}
