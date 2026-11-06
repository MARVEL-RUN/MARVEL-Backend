package kr.co.teambrain.marvelrun.user.participantauth.command.application.dto;

/**
 * 참가자 로그인·재발급 응답 본문. 토큰은 응답 헤더로 준다.
 *
 * @param registrationId 로그인에 쓴 주문(재발급은 대표 주문). 프론트가 마이페이지 API로 옮길 때까지 남기고 뒤에 지운다
 * @param orderCount     이 주문자의 주문 수(취소 포함). 2 이상이면 주문 목록을 보여 준다
 */
public record ParticipantLoginResponse(
        String registrationId,
        String eventId,
        String name,
        int orderCount
) {
}
