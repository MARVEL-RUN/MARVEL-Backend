package kr.co.teambrain.marvelrun.admin.event.query.dto.response;

import lombok.Builder;

/**
 * 관리자 서버 신청자 관리의 대회 목록 조회 응답 DTO[cite: 13]
 */
@Builder
public record EventListResponse(
        String eventId,              // 대회 식별자 (관리 버튼 클릭 시 활용)
        String eventName,            // 대회명
        String registrationType,     // 신청 유형 (EventStatus 대체)
        String registrationPeriod    // 접수 기간 (yyyy.MM.dd ~ yyyy.MM.dd 포맷)
) {
}