package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationModificationAccessContext;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationModificationCandidateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationPolicyContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.dto.RegistrationPolicyInput;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.dto.RegistrationPolicySelection;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.dto.RegistrationPolicyValidationResult;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.loader.RegistrationPolicyLoader;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategoryCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategorySouvenirCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 개인 수정 요청의 변경 후 후보 상태를 검증한다.
 *
 * Access Validator가 재인증한 Context를 입력받으며,
 * 자기 자신 제외 중복검사와 전체 수정에 필요한 참가 정책검증을 수행한다.
 *
 * 검증 중 현재 Registration Entity를 변경하지 않는다.
 */
@Component
public class RegistrationModificationCandidateValidator
        extends AbstractRegistrationApplyValidator {

    private final RegistrationUniqueInfoValidator uniqueInfoValidator;

    /**
     * 공통 조회·정책 검증과 수정 전용 중복검사 의존성을 연결한다.
     */
    public RegistrationModificationCandidateValidator(
            RegistrationCommandRepository registrationCommandRepository,
            EventCommandRepository eventCommandRepository,
            EventCategoryCommandRepository eventCategoryCommandRepository,
            EventCategorySouvenirCommandRepository eventCategorySouvenirCommandRepository,
            RegistrationPolicyLoader registrationPolicyLoader,
            RegistrationPolicyValidator registrationPolicyValidator,
            RegistrationUniqueInfoValidator uniqueInfoValidator
    ) {
        super(
                registrationCommandRepository,
                eventCommandRepository,
                eventCategoryCommandRepository,
                eventCategorySouvenirCommandRepository,
                registrationPolicyLoader,
                registrationPolicyValidator
        );

        this.uniqueInfoValidator = uniqueInfoValidator;
    }

    /**
     * 개인 수정 요청으로 변경 후 후보 상태를 구성하고
     * 신규 중복신청 검사를 제외한 참가 정책 전체를 다시 수행한다.
     *
     * 검증 완료 전에는 현재 Registration Entity를 변경하지 않는다.
     */
    public RegistrationModificationCandidateContext validate(
            RegistrationModificationAccessContext accessContext
    ) {
        Registration currentRegistration =
                accessContext.registration();

        RegistrationModificationRequest request =
                accessContext.request();

        validateEvent(
                accessContext.event(),
                accessContext.now()
        );

        uniqueInfoValidator.validateOtherActive(accessContext.event().getId(), currentRegistration.getId(),
                request.name(), request.phNum(), request.birth());

        Set<String> souvenirIds =
                collectRequestedSouvenirIds(
                        request.selectedSouvenirList()
                );

        SelectionData selections =
                loadSelections(
                        accessContext.event(),
                        Map.of(
                                request.eventCategoryId(),
                                souvenirIds
                        )
                );

        RegistrationPolicyContext policies =
                loadPolicies(
                        accessContext.event(),
                        selections
                );

        RegistrationPolicyValidationResult validated =
                validateParticipantSelection(
                        accessContext.event(),
                        toPolicyInput(request),
                        selections,
                        policies,
                        accessContext.now().toLocalDate()
                );

        return new RegistrationModificationCandidateContext(
                accessContext.event(),
                currentRegistration,
                validated.eventCategory(),
                validated.souvenirJsons(),
                request,
                accessContext.now()
        );
    }

    /**
     * 개인 수정 후보값에서 참가 정책검증에 필요한 값만 추출한다.
     *
     * 소유권 확인용 access와 비밀번호는 정책 입력에 포함하지 않는다.
     */
    private RegistrationPolicySelection toPolicyInput(
            RegistrationModificationRequest request
    ) {
        return new RegistrationPolicySelection(
                request.eventCategoryId(),
                request.selectedSouvenirList(),
                new RegistrationPolicyInput(
                        request.birth(),
                        request.guardianName(),
                        request.guardianConsent()
                )
        );
    }
}
