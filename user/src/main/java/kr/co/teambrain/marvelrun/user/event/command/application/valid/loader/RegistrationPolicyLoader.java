package kr.co.teambrain.marvelrun.user.event.command.application.valid.loader;


import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationPolicyContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.policy.EventCategoryRegistrationPolicy;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.policy.EventCategorySouvenirPolicy;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.policy.EventRegistrationPolicy;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategoryRegistrationPolicyRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategorySouvenirPolicyRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventRegistrationPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/* ___Policy는 user 서버에서 수정할 필요가 없다. 그렇다고 query에 두기엔 read 작업 목적이라고 보기도 애매함.
* command에 두고, 오직 신청 동작 진행을 위한 정책 '조회' 목적으로 활용하므로 service가 아닌 loader로 호칭.
*  */

/**
 * 하나의 신청 작업에 필요한 정책을 일괄 조회한다.
 *
 * ApplyValidator가 조회한 종목 및 기념품 매핑의 ID를 전달받는다.
 * 개인 신청과 단체 신청 모두 작업당 한 번 호출한다.
 *
 * 정책의 존재 여부와 조회 결과의 구성을 확인하며,
 * 참가자의 출생일, 선택 사이즈, 보호자 정보에 대한 판단은
 * RegistrationPolicyValidator가 수행한다.
 *
 * 현재 시각을 조회하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class RegistrationPolicyLoader {

    private final EventRegistrationPolicyRepository
            eventRegistrationPolicyRepository;

    private final EventCategoryRegistrationPolicyRepository
            eventCategoryRegistrationPolicyRepository;

    private final EventCategorySouvenirPolicyRepository
            eventCategorySouvenirPolicyRepository;

    /**
     * 해당 신청 작업에 필요한 정책을 조회한다.
     *
     * categoryIds는 참가자들이 선택한 종목 ID의 집합이다.
     * mappingIds는 조회 및 소속 검증을 마친
     * EventCategorySouvenir ID의 집합이다.
     *
     * 대회 정책과 각 종목 정책은 반드시 존재해야 한다.
     * 기념품 매핑별 추가 정책은 없을 수 있으며,
     * 이 경우 해당 매핑의 정책 목록은 빈 목록으로 반환한다.
     */
    public RegistrationPolicyContext load(
            String eventId,
            Set<String> categoryIds,
            Set<String> mappingIds
    ) {
        EventRegistrationPolicy eventPolicy =
                eventRegistrationPolicyRepository
                        .findByEventId(eventId)
                        .orElseThrow(
                                this::configurationError
                        );

        Map<String, EventCategoryRegistrationPolicy> categoryPolicies =
                loadCategoryPolicies(
                        eventId,
                        categoryIds
                );

        Map<String, List<EventCategorySouvenirPolicy>> souvenirPolicies =
                loadSouvenirPolicies(
                        eventId,
                        mappingIds
                );

        return new RegistrationPolicyContext(
                eventPolicy,
                categoryPolicies,
                souvenirPolicies
        );
    }

    /**
     * 요청에 포함된 종목의 정책을 한 번에 조회한다.
     *
     * 출생일 제한이 없는 종목도 정책 행은 존재해야 한다.
     * 제한 없음은 정책 행의 허용 출생일 범위 값으로 표현한다.
     */
    private Map<String, EventCategoryRegistrationPolicy> loadCategoryPolicies(
            String eventId,
            Set<String> categoryIds
    ) {
        if (categoryIds.isEmpty()) {
            return Map.of();
        }

        List<EventCategoryRegistrationPolicy> policies =
                eventCategoryRegistrationPolicyRepository
                        .findAllByEventIdAndCategoryIds(
                                eventId,
                                categoryIds
                        );

        Map<String, EventCategoryRegistrationPolicy> result =
                new HashMap<>();

        for (EventCategoryRegistrationPolicy policy : policies) {
            String categoryId =
                    policy.getEventCategory().getId();

            if (!categoryIds.contains(categoryId)) {
                throw configurationError();
            }

            EventCategoryRegistrationPolicy previous =
                    result.putIfAbsent(
                            categoryId,
                            policy
                    );

            if (previous != null) {
                throw configurationError();
            }
        }

        if (!result.keySet().equals(categoryIds)) {
            throw configurationError();
        }

        return result;
    }

    /**
     * 요청에 포함된 기념품 매핑의 추가 정책을 한 번에 조회한다.
     *
     * 매핑 하나에 여러 정책이 존재할 수 있으므로 List로 묶는다.
     * 추가 정책이 없는 매핑도 빈 목록으로 결과에 포함한다.
     */
    private Map<String, List<EventCategorySouvenirPolicy>> loadSouvenirPolicies(
            String eventId,
            Set<String> mappingIds
    ) {
        if (mappingIds.isEmpty()) {
            return Map.of();
        }

        Map<String, List<EventCategorySouvenirPolicy>> result =
                new HashMap<>();

        for (String mappingId : mappingIds) {
            result.put(
                    mappingId,
                    new ArrayList<>()
            );
        }

        List<EventCategorySouvenirPolicy> policies =
                eventCategorySouvenirPolicyRepository
                        .findAllByEventIdAndMappingIds(
                                eventId,
                                mappingIds
                        );

        Set<String> loadedPolicyIds =
                new HashSet<>();

        for (EventCategorySouvenirPolicy policy : policies) {
            String mappingId =
                    policy.getEventCategorySouvenir().getId();

            List<EventCategorySouvenirPolicy> mappingPolicies =
                    result.get(mappingId);

            if (mappingPolicies == null) {
                throw configurationError();
            }

            if (!loadedPolicyIds.add(policy.getId())) {
                throw configurationError();
            }

            mappingPolicies.add(policy);
        }

        return result;
    }

    private CustomException configurationError() {
        return new CustomException(
                ErrorCode.REGISTRATION_POLICY_CONFIGURATION_ERROR
        );
    }
}