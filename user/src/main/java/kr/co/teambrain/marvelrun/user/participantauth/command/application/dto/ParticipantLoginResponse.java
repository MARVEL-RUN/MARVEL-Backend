package kr.co.teambrain.marvelrun.user.participantauth.command.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 참가자 로그인·재발급 응답 본문. 토큰은 응답 헤더로 준다.
 *
 * @param registrationId 로그인에 쓴 주문(재발급은 대표 주문). 프론트가 마이페이지 API로 옮길 때까지 남기고 뒤에 지운다
 * @param orderCount     이 주문자의 주문 수(취소 포함). 2 이상이면 주문 목록을 보여 준다
 */
public record ParticipantLoginResponse(
        @Schema(description = "로그인에 쓴 주문(재발급은 대표 주문). 프론트가 마이페이지 API로 옮길 때까지만 남기고 뒤에 지운다")
        String registrationId,
        @Schema(description = "대회 id")
        String eventId,
        @Schema(description = "주문자 이름")
        String name,
        @Schema(description = "이 주문자의 주문 수(취소 포함). 2 이상이면 주문 목록을 보여 준다")
        int orderCount
) {
}
