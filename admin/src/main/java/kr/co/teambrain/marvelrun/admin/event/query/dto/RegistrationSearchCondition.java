package kr.co.teambrain.marvelrun.admin.event.query.dto;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;

/**
 * 관리자 서버 신청 목록 검색 조건 DTO
 */
public record RegistrationSearchCondition(

        String eventId, //대회 필터 (Event PK)
        String type,              // 전체 유형 필터 (예: "PERSONAL" 또는 "ORGANIZATION")
        String eventCategoryId,   // 특정 코스 필터 (EventCategory의 PK)
        RegistrationStatus status,// 신청 진행 상태 필터
        String keyword            // 통합 검색어 (이름, 단체명, 주문번호, 연락처)
) {
}