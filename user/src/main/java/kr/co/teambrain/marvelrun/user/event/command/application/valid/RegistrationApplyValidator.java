package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationCreateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationPolicyContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.dto.RegistrationPolicyInput;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.loader.RegistrationPolicyLoader;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategoryCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategorySouvenirCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registration 생성 신청에 필요한 도메인 객체를 조회하고,
 * 신청 가능 여부를 검증한 뒤 RegistrationCreateContext를 생성한다.
 */
@Component
public class RegistrationApplyValidator extends AbstractRegistrationApplyValidator {

    public RegistrationApplyValidator(
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
     * 개인 신청 생성 시 사용하는 최상위 검증 진입점.
     *
     * Service는 개별 검증 로직을 알지 않고,
     * 검증 완료된 Context만 전달받는다.
     */
    public RegistrationCreateContext validate(
            String eventId,
            RegistrationCreateRequest request,
            LocalDateTime now
    ) {
        Event event =
                getEvent(eventId);

        validateEvent(
                event,
                now
        );

        validateAlreadyRegisteredParticipant(
                eventId,
                request.name(),
                request.phNum(),
                request.birth()
        );

        Set<String> souvenirIds =
                collectRequestedSouvenirIds(
                        request.selectedSouvenirList()
                );

        SelectionData selections =
                loadSelections(
                        event,
                        Map.of(
                                request.eventCategoryId(),
                                souvenirIds
                        )
                );

        RegistrationPolicyContext policies =
                loadPolicies(
                        event,
                        selections
                );

        LocalDate birth =
                registrationPolicyValidator.validateParticipant(
                        event,
                        policies.eventPolicy(),
                        new RegistrationPolicyInput(
                                request.birth(),
                                request.guardianName(),
                                request.guardianConsent()
                        ),
                        now.toLocalDate()
                );



        EventCategory eventCategory =
                selections.categories().get(
                        request.eventCategoryId()
                );

        registrationPolicyValidator.validateCategoryBirth(
                eventCategory,
                policies.categoryPolicies().get(eventCategory.getId()),
                birth
        );

        List<SouvenirJson> souvenirJsons =
                validateSouvenirs(
                        request.selectedSouvenirList(),
                        selections.mappingsByCategory().get(
                                eventCategory.getId()
                        ),
                        birth,
                        policies
                );

        return new RegistrationCreateContext(
                event,
                eventCategory,
                souvenirJsons
        );
    }
}