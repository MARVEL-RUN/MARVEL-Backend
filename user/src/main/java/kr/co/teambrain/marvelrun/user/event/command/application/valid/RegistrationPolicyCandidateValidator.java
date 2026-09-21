package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationPolicyContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.dto.RegistrationPolicyCandidateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.dto.RegistrationPolicyCandidateResult;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.loader.RegistrationPolicyLoader;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategoryCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategorySouvenirCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 생성·수정 후보에 동일한 참가 정책을 적용한다.
 *
 * 기존 01의 조회·소속·활성·기념품 검증과 정책 Validator를 재사용한다.
 * 인증과 참가자 중복검사는 각 생성·수정 Validator가 담당한다.
 *
 * Entity 변경, Pricing, Capacity 확보 및 금융 처리는 수행하지 않는다.
 * 조회 결과는 한 호출의 지역변수로만 보관한다.
 */
@Component
public class RegistrationPolicyCandidateValidator
        extends AbstractRegistrationApplyValidator {

    /**
     * 기존 공통 조회 및 정책검증 의존성을 연결한다.
     */
    public RegistrationPolicyCandidateValidator(
            RegistrationCommandRepository registrationCommandRepository,
            EventCommandRepository eventCommandRepository,
            EventCategoryCommandRepository eventCategoryCommandRepository,
            EventCategorySouvenirCommandRepository eventCategorySouvenirCommandRepository,
            RegistrationPolicyLoader registrationPolicyLoader,
            RegistrationPolicyValidator registrationPolicyValidator
    ) {
        super(
                registrationCommandRepository,
                eventCommandRepository,
                eventCategoryCommandRepository,
                eventCategorySouvenirCommandRepository,
                registrationPolicyLoader,
                registrationPolicyValidator
        );
    }

    /**
     * 한 참가자의 변경 후 후보 상태를 검증한다.
     */
    public RegistrationPolicyCandidateResult validate(
            Event event,
            RegistrationPolicyCandidateRequest candidate,
            LocalDateTime now
    ) {
        return validateAll(
                event,
                List.of(candidate),
                now
        ).get(0);
    }

    /**
     * 참가자 전체를 입력 순서대로 검증한다.
     *
     * 같은 종목의 매핑과 정책을 함께 조회한 뒤 참가자별로 재사용한다.
     * 하나라도 실패하면 예외를 전파하며 부분 성공 결과를 반환하지 않는다.
     */
    public List<RegistrationPolicyCandidateResult> validateAll(
            Event event,
            List<RegistrationPolicyCandidateRequest> candidates,
            LocalDateTime now
    ) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "정책검증 대상 참가자가 비어 있습니다."
            );
        }

        validateEvent(event, now);

        Map<String, Set<String>> requestedSouvenirIdsByCategory =
                new LinkedHashMap<>();

        for (RegistrationPolicyCandidateRequest candidate : candidates) {
            Set<String> souvenirIds =
                    collectRequestedSouvenirIds(
                            candidate.selectedSouvenirList()
                    );

            requestedSouvenirIdsByCategory
                    .computeIfAbsent(
                            candidate.eventCategoryId(),
                            ignored -> new HashSet<>()
                    )
                    .addAll(souvenirIds);
        }

        SelectionData selections =
                loadSelections(
                        event,
                        requestedSouvenirIdsByCategory
                );

        RegistrationPolicyContext policies =
                loadPolicies(
                        event,
                        selections
                );

        List<RegistrationPolicyCandidateResult> results =
                new ArrayList<>(candidates.size());

        for (RegistrationPolicyCandidateRequest candidate : candidates) {
            LocalDate birth =
                    registrationPolicyValidator.validateParticipant(
                            event,
                            policies.eventPolicy(),
                            candidate.participant(),
                            now.toLocalDate()
                    );

            EventCategory category =
                    selections.categories().get(
                            candidate.eventCategoryId()
                    );

            registrationPolicyValidator.validateCategoryBirth(
                    category,
                    policies.categoryPolicies().get(category.getId()),
                    birth
            );

            results.add(
                    new RegistrationPolicyCandidateResult(
                            category,
                            birth,
                            validateSouvenirs(
                                    candidate.selectedSouvenirList(),
                                    selections.mappingsByCategory().get(
                                            category.getId()
                                    ),
                                    birth,
                                    policies
                            )
                    )
            );
        }

        return List.copyOf(results);
    }

    /**
     * 단체장은 대회 당일 기준 만 19세 이상이어야 한다.
     *
     * 최초 단체 신청과 단체 수정에서 동일한 기준을 사용한다.
     */
    public void validateOrganizationLeaderAge(
            LocalDate leaderBirth,
            LocalDate eventDate
    ) {
        if (leaderBirth == null) {
            throw new CustomException(
                    ErrorCode.ORGANIZATION_LEADER_BIRTH_REQUIRED
            );
        }

        if (eventDate.isBefore(leaderBirth.plusYears(19))) {
            throw new CustomException(
                    ErrorCode.ORGANIZATION_LEADER_MUST_BE_ADULT
            );
        }
    }
}