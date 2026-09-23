package kr.co.teambrain.marvelrun.admin.event.command.application.valid;

import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.event.command.application.context.RegistrationPolicyContext;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategorySouvenir;
import kr.co.teambrain.marvelrun.admin.event.command.application.valid.dto.RegistrationPolicyCandidateRequest;
import kr.co.teambrain.marvelrun.admin.event.command.application.valid.dto.RegistrationPolicyCandidateResult;
import kr.co.teambrain.marvelrun.admin.event.command.application.valid.loader.RegistrationPolicyLoader;
import kr.co.teambrain.marvelrun.admin.event.command.repository.EventCategoryCommandRepository;
import kr.co.teambrain.marvelrun.admin.event.command.repository.EventCategorySouvenirCommandRepository;
import kr.co.teambrain.marvelrun.admin.payment.command.repository.RefundEventRepository;
import kr.co.teambrain.marvelrun.admin.payment.command.repository.RefundRegistrationRepository;
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
    private final EventCategoryCommandRepository adminCategories;
    private final EventCategorySouvenirCommandRepository adminMappings;

    /**
     * 기존 공통 조회 및 정책검증 의존성을 연결한다.
     */
    public RegistrationPolicyCandidateValidator(
            RefundRegistrationRepository registrationCommandRepository,
            RefundEventRepository eventCommandRepository,
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
        this.adminCategories = eventCategoryCommandRepository;
        this.adminMappings = eventCategorySouvenirCommandRepository;
    }

    /**
     * 관리자 변경은 연령·보호자·출생일별 기념품·활성/접수기간 정책을 적용하지 않는다.
     * 실제 날짜, 대회 소속, 종목-기념품 매핑, 중복 선택, 존재하는 사이즈는 반드시 검증한다.
     * 정원 실수량 및 금융 검증은 같은 트랜잭션의 후속 단계에서 수행한다.
     */
    public List<RegistrationPolicyCandidateResult> validateAdminAdjustment(Event event,
            List<RegistrationPolicyCandidateRequest> candidates, LocalDateTime now) {
        if (event == null || event.getId() == null || event.getStartDate() == null || now == null
                || candidates == null || candidates.isEmpty()) {
            throw new CustomException(ErrorCode.REGISTRATION_POLICY_CONFIGURATION_ERROR);
        }
        Map<String, EventCategory> categories = new LinkedHashMap<>();
        Map<String, Map<String, EventCategorySouvenir>> mappings = new LinkedHashMap<>();
        List<RegistrationPolicyCandidateResult> result = new ArrayList<>();
        for (var candidate : candidates) {
            Set<String> selected = collectRequestedSouvenirIds(candidate.selectedSouvenirList());
            EventCategory category = categories.computeIfAbsent(candidate.eventCategoryId(), id -> {
                EventCategory row = adminCategories.findById(id)
                        .orElseThrow(() -> new CustomException(ErrorCode.EVENT_CATEGORY_NOT_FOUND));
                if (!event.getId().equals(row.getEvent().getId())) {
                    throw new CustomException(ErrorCode.EVENT_CATEGORY_NOT_FOUND);
                }
                return row;
            });
            Map<String, EventCategorySouvenir> bySouvenir = mappings.computeIfAbsent(category.getId(), id -> {
                Map<String, EventCategorySouvenir> values = new LinkedHashMap<>();
                for (var mapping : adminMappings.findAllMappingsByCategoryId(id)) {
                    if (!id.equals(mapping.getEventCategory().getId()) || mapping.getSouvenir() == null
                            || !event.getId().equals(mapping.getSouvenir().getEvent().getId())
                            || values.putIfAbsent(mapping.getSouvenir().getId(), mapping) != null) {
                        throw new CustomException(ErrorCode.INVALID_EVENT_CATEGORY_SOUVENIR);
                    }
                }
                return values;
            });
            if (!bySouvenir.keySet().equals(selected)) {
                throw new CustomException(ErrorCode.INVALID_EVENT_CATEGORY_SOUVENIR,
                        " 선택한 종목에 연결된 기념품 목록과 일치하도록 다시 선택해 주세요.");
            }
            LocalDate birth = registrationPolicyValidator.parseBirth(candidate.participant().birth(), now.toLocalDate());
            List<kr.co.teambrain.marvelrun.common.json_object.SouvenirJson> normalized = new ArrayList<>();
            for (var choice : candidate.selectedSouvenirList()) {
                normalized.add(new kr.co.teambrain.marvelrun.common.json_object.SouvenirJson(choice.souvenirId(),
                        validateAndNormalizeSize(bySouvenir.get(choice.souvenirId()).getSouvenir(), choice.selectedSize())));
            }
            result.add(new RegistrationPolicyCandidateResult(category, birth, List.copyOf(normalized)));
        }
        return List.copyOf(result);
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

        // 관리자 환불은 접수기간/paymentDeadline과 독립적이다. 종목·기념품·보호자 정책은 그대로 검증한다.
        if (event == null || event.getId() == null || event.getStartDate() == null || now == null) {
            throw new CustomException(ErrorCode.REGISTRATION_POLICY_CONFIGURATION_ERROR);
        }

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
     * 단체장은 대회 당일 기준 만 14세 이상이어야 한다.
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

        if (eventDate.isBefore(leaderBirth.plusYears(14))) {
            throw new CustomException(
                    ErrorCode.ORGANIZATION_LEADER_MUST_BE_ADULT
            );
        }
    }
    /** 저장 단체장 날짜 파싱과 대회일 나이 기준을 사용자와 같은 검증 경로로 적용한다. */
    public void validateStoredLeader(String birth, LocalDate eventDate, LocalDate applicationDate) {
        validateStoredOrganizationLeaderAge(birth, eventDate, applicationDate);
    }
}
