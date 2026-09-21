package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.context.OrgRegistrationModificationAccessContext;
import kr.co.teambrain.marvelrun.user.event.command.application.context.OrgRegistrationModificationCandidateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationPolicyContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationModificationParticipantRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.dto.RegistrationPolicyInput;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.dto.RegistrationPolicySelection;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.dto.RegistrationPolicyValidationResult;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.loader.RegistrationPolicyLoader;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategoryCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategorySouvenirCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 단체 수정의 최종 구성원 목록을 후보 상태로 검증한다.
 *
 * 기존·신규 참가자 중복과 단체장·참가 정책을 확인하고,
 * 현재 Entity를 변경하지 않은 채 전체 후보 Context를 반환한다.
 */
@Component
public class OrgRegistrationModificationCandidateValidator
        extends AbstractRegistrationApplyValidator {

    private final RegistrationCommandRepository
            registrationCommandRepository;

    /**
     * 공통 조회·정책 검증과 수정 전용 중복검사 의존성을 연결한다.
     */
    public OrgRegistrationModificationCandidateValidator(
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

        this.registrationCommandRepository =
                registrationCommandRepository;
    }

    /**
     * 단체 수정 요청의 최종 구성원 목록을 기준으로
     * 기존/신규 참가자를 구분하고 변경 후 후보 상태를 구성한다.
     *
     * 각 참가자는 개인 신청과 동일한 01 참가자 정책을 다시 검증하며,
     * 검증 완료 전에는 현재 Registration Entity를 변경하지 않는다.
     *
     * @param accessContext 단체 재인증과 기존 ID 귀속검증을 통과한 Context
     * @return 전체 구성원의 정책검증 완료 후보 Context
     */
    public OrgRegistrationModificationCandidateContext validate(
            OrgRegistrationModificationAccessContext accessContext
    ) {
        validateEvent(
                accessContext.event(),
                accessContext.now()
        );

        Organization organization =
                accessContext.organization();

        validateStoredOrganizationLeaderAge(
                organization.getLeaderBirth(),
                accessContext.event().getStartDate().toLocalDate(),
                accessContext.now().toLocalDate()
        );

        validateRequestDuplicates(
                accessContext.request().registrations()
        );

        Map<String, Registration> currentById =
                createCurrentRegistrationMap(
                        accessContext.currentRegistrations()
                );

        Map<String, Set<String>> requestedSouvenirIdsByCategory =
                new LinkedHashMap<>();

        for (OrgRegistrationModificationParticipantRequest participantRequest
                : accessContext.request().registrations()) {

            Set<String> souvenirIds =
                    collectRequestedSouvenirIds(
                            participantRequest.selectedSouvenirList()
                    );

            requestedSouvenirIdsByCategory
                    .computeIfAbsent(
                            participantRequest.eventCategoryId(),
                            ignored -> new HashSet<>()
                    )
                    .addAll(souvenirIds);
        }

        SelectionData selections =
                loadSelections(
                        accessContext.event(),
                        requestedSouvenirIdsByCategory
                );

        RegistrationPolicyContext policies =
                loadPolicies(
                        accessContext.event(),
                        selections
                );

        List<OrgRegistrationModificationCandidateContext.ParticipantCandidate>
                candidateRegistrations =
                new ArrayList<>(
                        accessContext.request().registrations().size()
                );

        for (OrgRegistrationModificationParticipantRequest participantRequest
                : accessContext.request().registrations()) {

            Registration currentRegistration =
                    resolveCurrentRegistration(
                            currentById,
                            participantRequest
                    );

            validateDatabaseDuplicate(
                    accessContext.event().getId(),
                    currentRegistration,
                    participantRequest
            );

            RegistrationPolicyValidationResult validated =
                    validateParticipantSelection(
                            accessContext.event(),
                            toPolicyInput(
                                    organization,
                                    participantRequest
                            ),
                            selections,
                            policies,
                            accessContext.now().toLocalDate()
                    );

            candidateRegistrations.add(
                    new OrgRegistrationModificationCandidateContext
                            .ParticipantCandidate(
                            currentRegistration,
                            participantRequest,
                            validated.eventCategory(),
                            validated.souvenirJsons()
                    )
            );
        }

        return new OrgRegistrationModificationCandidateContext(
                accessContext.event(),
                organization,
                accessContext.currentRegistrations(),
                candidateRegistrations,
                accessContext.request(),
                accessContext.now()
        );
    }

    /**
     * 수정 전 현재 Organization 구성원을
     * Registration ID 기준 Map으로 구성한다.
     */
    private Map<String, Registration> createCurrentRegistrationMap(
            List<Registration> currentRegistrations
    ) {
        Map<String, Registration> currentById =
                new HashMap<>(currentRegistrations.size());

        for (Registration registration : currentRegistrations) {
            currentById.put(
                    registration.getId(),
                    registration
            );
        }

        return currentById;
    }

    /**
     * 수정 요청의 registrationId를 기준으로
     * 기존 참가자와 신규 참가자를 구분한다.
     *
     * registrationId가 null이면 신규 참가자다.
     *
     * Access Validator에서 이미 현재 Organization 소속 ID만
     * 허용하지만, Candidate 단계에서도 방어적으로 다시 확인한다.
     */
    private Registration resolveCurrentRegistration(
            Map<String, Registration> currentById,
            OrgRegistrationModificationParticipantRequest request
    ) {
        if (request.registrationId() == null) {
            return null;
        }

        Registration currentRegistration =
                currentById.get(request.registrationId());

        if (currentRegistration == null) {
            throw new CustomException(
                    ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET
            );
        }

        return currentRegistration;
    }

    /**
     * 수정 후 최종 구성원 목록 자체에
     * 동일 이름·전화번호·생년월일 참가자가
     * 두 번 포함되지 않는지 검증한다.
     */
    private void validateRequestDuplicates(
            List<OrgRegistrationModificationParticipantRequest> requests
    ) {
        Set<ParticipantUniqueInfo> uniqueInfos =
                new HashSet<>(requests.size());

        for (OrgRegistrationModificationParticipantRequest request : requests) {
            ParticipantUniqueInfo uniqueInfo =
                    new ParticipantUniqueInfo(
                            request.name(),
                            request.phNum(),
                            request.birth()
                    );

            if (!uniqueInfos.add(uniqueInfo)) {
                throw new CustomException(
                        ErrorCode.REGISTRATION_ALREADY_EXISTS
                );
            }
        }
    }

    /**
     * 기존 구성원과 신규 구성원의 수정 후 식별정보가
     * DB의 다른 Registration과 충돌하는지 검증한다.
     *
     * 기존 구성원은 자기 자신을 제외하고 검사하며,
     * 신규 구성원은 일반 신규 신청과 동일하게 전체를 검사한다.
     */
    private void validateDatabaseDuplicate(
            String eventId,
            Registration currentRegistration,
            OrgRegistrationModificationParticipantRequest request
    ) {
        boolean duplicated;

        if (currentRegistration == null) {
            duplicated =
                    registrationCommandRepository
                            .existsByEventIdAndUniqueInfo(
                                    eventId,
                                    request.name(),
                                    request.phNum(),
                                    request.birth()
                            );
        } else {
            duplicated =
                    registrationCommandRepository
                            .existsOtherActiveByEventIdAndUniqueInfo(
                                    eventId,
                                    currentRegistration.getId(),
                                    request.name(),
                                    request.phNum(),
                                    request.birth()
                            );
        }

        if (duplicated) {
            throw new CustomException(
                    ErrorCode.REGISTRATION_ALREADY_EXISTS
            );
        }
    }

    /**
     * 단체 구성원의 후보값と 현재 단체장 보호자 정보를
     * 공통 참가 정책 입력으로 구성한다.
     *
     * 비밀번호와 생성 Request는 정책검증에 사용하지 않는다.
     */
    private RegistrationPolicySelection toPolicyInput(
            Organization organization,
            OrgRegistrationModificationParticipantRequest request
    ) {
        return new RegistrationPolicySelection(
                request.eventCategoryId(),
                request.selectedSouvenirList(),
                new RegistrationPolicyInput(
                        request.birth(),
                        organization.getLeaderName(),
                        organization.isGuardianConsent()
                )
        );
    }

    /**
     * 단체 수정 최종목록 내부의 동일 참가자 여부를 판단하기 위한
     * 이름·전화번호·생년월일 조합이다.
     */
    private record ParticipantUniqueInfo(
            String name,
            String phNum,
            String birth
    ) {
    }
}