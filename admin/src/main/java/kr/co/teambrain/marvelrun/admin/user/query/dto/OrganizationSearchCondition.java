package kr.co.teambrain.marvelrun.admin.user.query.dto;

public record OrganizationSearchCondition(
        String eventId,     // 대회 필터 (필수)
        String keyword      // 통합 검색어 (단체명, 대표자명)
) {
}