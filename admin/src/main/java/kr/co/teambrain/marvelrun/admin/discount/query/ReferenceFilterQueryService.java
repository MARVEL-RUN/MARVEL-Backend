package kr.co.teambrain.marvelrun.admin.discount.query;

import kr.co.teambrain.marvelrun.admin.discount.domain.ReferenceFilter;
import kr.co.teambrain.marvelrun.admin.discount.repository.ReferenceFilterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 대회별 선택지와 목록에 쓰는 필터 정보를 일괄 조회한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReferenceFilterQueryService {
    private final ReferenceFilterRepository filters;

    /** 우선순위는 노출 상한으로만 사용하고 표시 순서는 sortOrder를 따른다. */
    public Page<ReferenceFilterSummaryResponse> getEventReferenceFilters(String eventId, Integer maxPriority,
            String keyword, int page, int size) {
        Specification<ReferenceFilter> specification = (root, query, cb) -> cb.equal(root.get("eventId"), eventId);
        if (maxPriority != null) {
            specification = specification.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("priority"), maxPriority));
        }
        if (keyword != null && !keyword.isBlank()) {
            String pattern = "%" + keyword.strip().toUpperCase(Locale.ROOT).replace("\\", "\\\\")
                    .replace("%", "\\%").replace("_", "\\_") + "%";
            specification = specification.and((root, query, cb) -> cb.like(cb.upper(root.get("code")), pattern, '\\'));
        }
        return filters.findAll(specification, PageRequest.of(page, size, Sort.by("sortOrder", "code", "id")))
                .map(ReferenceFilterSummaryResponse::from);
    }

    /** 페이지에 사용된 참조 ID만 조회하여 참가자별 추가 조회를 피한다. */
    public Map<String, ReferenceFilterSummaryResponse> loadReferenceFilterSummaries(Collection<String> ids) {
        Map<String, ReferenceFilterSummaryResponse> result = new HashMap<>();
        filters.findAllById(ids.stream().filter(Objects::nonNull).distinct().toList())
                .forEach(filter -> result.put(filter.getId(), ReferenceFilterSummaryResponse.from(filter)));
        return result;
    }
}
