package kr.co.teambrain.marvelrun.admin.user.query.dto;

/** 대회별 활성 단체 목록과 참조필터 검색 조건이다. */
public record OrganizationSearchCondition(String eventId, String keyword, String referenceFilterId) {
    /** 필터를 지정하지 않는 기존 조회를 유지한다. */
    public OrganizationSearchCondition(String eventId, String keyword) {
        this(eventId, keyword, null);
    }
}