package kr.co.teambrain.marvelrun.admin.event.query.dto;

import lombok.Builder;

/**
 * 관리자 서버 특정 대회의 코스(EventCategory) 목록 조회 응답 DTO
 */
@Builder
public record EventCategoryResponse(
        String id,
        String name
) {
}