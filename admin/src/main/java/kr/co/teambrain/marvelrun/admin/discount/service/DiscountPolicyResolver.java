package kr.co.teambrain.marvelrun.admin.discount.service;

import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.discount.domain.DiscountAgeCondition;
import kr.co.teambrain.marvelrun.admin.discount.domain.DiscountLink;
import kr.co.teambrain.marvelrun.admin.discount.domain.DiscountPolicy;
import kr.co.teambrain.marvelrun.admin.discount.domain.DiscountPolicyCategory;
import kr.co.teambrain.marvelrun.admin.discount.domain.ReferenceFilter;
import kr.co.teambrain.marvelrun.admin.discount.repository.DiscountAgeConditionRepository;
import kr.co.teambrain.marvelrun.admin.discount.repository.DiscountLinkRepository;
import kr.co.teambrain.marvelrun.admin.discount.repository.DiscountPolicyCategoryRepository;
import kr.co.teambrain.marvelrun.admin.discount.repository.DiscountPolicyRepository;
import kr.co.teambrain.marvelrun.admin.discount.repository.ReferenceFilterRepository;
import kr.co.teambrain.marvelrun.admin.event.command.repository.EventCategoryCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 대회의 할인 설정을 일괄 읽고 계산 전에 참조 및 조건 정합성을 검증한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DiscountPolicyResolver {
    private final DiscountPolicyRepository policies;
    private final DiscountPolicyCategoryRepository targets;
    private final DiscountAgeConditionRepository ages;
    private final DiscountLinkRepository links;
    private final ReferenceFilterRepository filters;
    private final EventCategoryCommandRepository categories;

    /** 참가자 반복문 밖에서 재사용할 대회 정책 스냅샷을 구성한다. */
    public List<DiscountModels.Policy> resolveEventPolicies(String eventId) {
        List<DiscountPolicy> rows = policies.findByEventId(eventId);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> ids = rows.stream().map(DiscountPolicy::getId).toList();
        List<DiscountPolicyCategory> targetRows = targets.findByPolicyIdIn(ids);
        List<DiscountAgeCondition> ageRows = ages.findByPolicyIdIn(ids);
        Set<String> categoryIds = new HashSet<>();
        targetRows.forEach(target -> categoryIds.add(target.getEventCategoryId()));
        Set<String> validCategoryIds = new HashSet<>();
        categories.findAllById(categoryIds).forEach(category -> {
            if (eventId.equals(category.getEvent().getId())) {
                validCategoryIds.add(category.getId());
            }
        });
        if (!validCategoryIds.equals(categoryIds)) {
            throw new CustomException(ErrorCode.DISCOUNT_CONFIGURATION_ERROR);
        }

        // 행 간 조건은 FK만으로 보장되지 않으므로 스냅샷 생성 시 검증한다.
        List<DiscountModels.Policy> result = new ArrayList<>();
        for (DiscountPolicy row : rows) {
            Set<String> selected = new HashSet<>();
            targetRows.stream().filter(target -> row.getId().equals(target.getPolicyId()))
                    .forEach(target -> selected.add(target.getEventCategoryId()));
            List<DiscountAgeCondition> conditions = ageRows.stream()
                    .filter(age -> row.getId().equals(age.getPolicyId())).toList();
            validatePolicyShape(row, selected, conditions);
            DiscountAgeCondition age = conditions.isEmpty() ? null : conditions.getFirst();
            result.add(new DiscountModels.Policy(row.getId(), eventId, row.getRevision(), row.getStage(),
                    row.getCalculationMethod(), row.getRatePercent(), row.getFixedAmount(), row.getApplicationType(),
                    row.getCategoryScope(), Set.copyOf(selected), row.getMinimumGroupSize(),
                    age == null ? null : age.getMinAgeInclusive(), age == null ? null : age.getMaxAgeExclusive()));
        }
        return List.copyOf(result);
    }

    /** 저장된 링크 자격을 조회하며 신규 취득 기간을 다시 적용하지 않는다. */
    public DiscountLink resolveStoredLink(String linkId) {
        return links.findById(linkId).orElseThrow(() -> new CustomException(ErrorCode.DISCOUNT_CONFIGURATION_ERROR));
    }

    /** 링크 정책과 필터가 같은 대회에 속하는지 검증한다. */
    public DiscountModels.Policy resolveLinkPolicy(DiscountLink link, String eventId, List<DiscountModels.Policy> snapshot) {
        DiscountModels.Policy policy = snapshot.stream().filter(candidate -> candidate.id().equals(link.getPolicyId()))
                .findFirst().orElseThrow(() -> new CustomException(ErrorCode.DISCOUNT_CONFIGURATION_ERROR));
        ReferenceFilter filter = filters.findById(link.getReferenceFilterId())
                .orElseThrow(() -> new CustomException(ErrorCode.DISCOUNT_CONFIGURATION_ERROR));
        if (policy.stage() != DiscountModels.Stage.EXTERNAL || !eventId.equals(filter.getEventId())
                || link.getAcquisitionStartAt() == null || link.getAcquisitionEndAt() == null
                || !link.getAcquisitionStartAt().isBefore(link.getAcquisitionEndAt())) {
            throw new CustomException(ErrorCode.DISCOUNT_CONFIGURATION_ERROR);
        }
        return policy;
    }

    /** 지원하지 않는 조합은 할인 미적용으로 숨기지 않고 설정 오류로 반환한다. */
    private void validatePolicyShape(DiscountPolicy row, Set<String> selected, List<DiscountAgeCondition> conditions) {
        boolean internal = row.getStage() == DiscountModels.Stage.INTERNAL;
        if (row.getStage() == null || row.getApplicationType() == null || row.getCategoryScope() == null
                || row.getRevision() < 1
                || (row.getCategoryScope() == DiscountModels.CategoryScope.ALL && !selected.isEmpty())
                || (row.getCategoryScope() == DiscountModels.CategoryScope.SELECTED && selected.isEmpty())
                || (row.getMinimumGroupSize() != null && (row.getMinimumGroupSize() < 2
                    || row.getApplicationType() == DiscountModels.ApplicationType.PERSONAL))
                || (internal && (conditions.size() != 1 || row.getCalculationMethod() != DiscountModels.Method.FIXED_PRICE
                    || row.getFixedAmount() == null || row.getFixedAmount().signum() <= 0 || row.getRatePercent() != null))
                || (!internal && (!conditions.isEmpty() || row.getCalculationMethod() != DiscountModels.Method.PERCENT
                    || row.getRatePercent() == null || row.getRatePercent().signum() <= 0
                    || row.getRatePercent().compareTo(new BigDecimal("100")) >= 0 || row.getFixedAmount() != null))) {
            throw new CustomException(ErrorCode.DISCOUNT_CONFIGURATION_ERROR);
        }
        if (internal && (conditions.getFirst().getMinAgeInclusive() < 0
                || conditions.getFirst().getMaxAgeExclusive() <= conditions.getFirst().getMinAgeInclusive())) {
            throw new CustomException(ErrorCode.DISCOUNT_CONFIGURATION_ERROR);
        }
    }
}
