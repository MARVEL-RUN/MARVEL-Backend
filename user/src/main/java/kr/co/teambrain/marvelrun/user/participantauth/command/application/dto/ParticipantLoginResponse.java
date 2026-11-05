package kr.co.teambrain.marvelrun.user.participantauth.command.application.dto;

/** 참가자 로그인·재발급 응답 본문. 토큰은 응답 헤더로 준다. */
public record ParticipantLoginResponse(
        String registrationId,
        String eventId,
        String name
) {
}
