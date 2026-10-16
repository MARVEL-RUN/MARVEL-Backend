package kr.co.teambrain.marvelrun.admin.discount.query;

import kr.co.teambrain.marvelrun.admin.discount.domain.ReferenceFilter;

/** 저장된 필터 연결을 관리자에게 표시한다. */
public record ReferenceFilterSummaryResponse(String id, String code, String displayName, int priority, int sortOrder) {
    /** 설정 행을 외부 표기 DTO로 변환한다. */
    public static ReferenceFilterSummaryResponse from(ReferenceFilter filter) {
        return new ReferenceFilterSummaryResponse(filter.getId(), filter.getCode(), filter.getDisplayName(),
                filter.getPriority(), filter.getSortOrder());
    }
}
