package kr.co.teambrain.marvelrun.admin.discount.query;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 공통 필터 선택지만 제공하며 정책 관리 API는 포함하지 않는다. */
@RestController
@Validated
@RequiredArgsConstructor
public class ReferenceFilterQueryController {
    private final ReferenceFilterQueryService filters;

    /** 대회 범위와 선택적인 우선순위·코드 조건으로 선택지를 조회한다. */
    @GetMapping("/v1/admin/events/{eventId}/reference-filters")
    public ResponseEntity<Page<ReferenceFilterSummaryResponse>> getEventReferenceFilters(
        @PathVariable("eventId") String eventId,
        @RequestParam(name = "maxPriority", required = false) @Min(0) Integer maxPriority,
        @RequestParam(name = "keyword", required = false) @Size(max = 50) String keyword,
        @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
        @RequestParam(name = "size", defaultValue = "50") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(filters.getEventReferenceFilters(eventId, maxPriority, keyword, page, size));
    }
}
