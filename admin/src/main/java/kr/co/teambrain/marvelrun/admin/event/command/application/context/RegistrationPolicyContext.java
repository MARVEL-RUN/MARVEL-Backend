package kr.co.teambrain.marvelrun.admin.event.command.application.context;


import kr.co.teambrain.marvelrun.admin.event.command.application.domain.policy.EventCategoryRegistrationPolicy;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.policy.EventCategorySouvenirPolicy;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.policy.EventRegistrationPolicy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 하나의 신청 작업에서 조회한 정책을 보관한다.
 *
 * 개인 신청과 단체 신청의 정책 검증에서 공통으로 사용한다.
 * 단체 신청에서는 참가자 전체가 같은 조회 결과를 재사용한다.
 *
 * 작업별 지역변수로 사용하며, singleton 필드나 static 필드에 보관하지 않는다.
 * Entity 자체를 복제하지 않으며, 정책을 담은 Map과 List의 변경을 막는다.
 */
public record RegistrationPolicyContext(
        EventRegistrationPolicy eventPolicy,
        Map<String, EventCategoryRegistrationPolicy> categoryPolicies,
        Map<String, List<EventCategorySouvenirPolicy>> souvenirPolicies
) {

    public RegistrationPolicyContext {
        Objects.requireNonNull(eventPolicy);
        Objects.requireNonNull(categoryPolicies);
        Objects.requireNonNull(souvenirPolicies);

        categoryPolicies = Map.copyOf(categoryPolicies);

        Map<String, List<EventCategorySouvenirPolicy>> copiedSouvenirPolicies =
                new HashMap<>();

        souvenirPolicies.forEach(
                (mappingId, policies) -> copiedSouvenirPolicies.put(
                        mappingId,
                        List.copyOf(policies)
                )
        );

        souvenirPolicies = Map.copyOf(copiedSouvenirPolicies);
    }
}