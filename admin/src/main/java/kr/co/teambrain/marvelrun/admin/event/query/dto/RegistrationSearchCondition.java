package kr.co.teambrain.marvelrun.admin.event.query.dto;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;

/** 신청 목록의 기존 조건과 실제 보유 참조필터를 함께 검색한다. */
public record RegistrationSearchCondition(String eventId, String type, String eventCategoryId,
        RegistrationStatus status, String keyword, String startGroupCode, Boolean startGroupUnselected,
        String referenceFilterId, String bibNumber) {
    /** 배번 조건 없는 기존 8개 인자 호출을 유지한다. */
    public RegistrationSearchCondition(String eventId, String type, String eventCategoryId,
            RegistrationStatus status, String keyword, String startGroupCode, Boolean startGroupUnselected,
            String referenceFilterId) {
        this(eventId, type, eventCategoryId, status, keyword, startGroupCode, startGroupUnselected,
                referenceFilterId, null);
    }
    /** 기존 그룹 조건 호출은 필터 없이 유지한다. */
    public RegistrationSearchCondition(String eventId, String type, String eventCategoryId,
            RegistrationStatus status, String keyword, String startGroupCode, Boolean startGroupUnselected) {
        this(eventId, type, eventCategoryId, status, keyword, startGroupCode, startGroupUnselected, null, null);
    }
    /** 기존 엑셀 요청은 추가 검색 조건 없이 유지한다. */
    public RegistrationSearchCondition(String eventId, String type, String eventCategoryId,
            RegistrationStatus status, String keyword) {
        this(eventId, type, eventCategoryId, status, keyword, null, null, null, null);
    }
}