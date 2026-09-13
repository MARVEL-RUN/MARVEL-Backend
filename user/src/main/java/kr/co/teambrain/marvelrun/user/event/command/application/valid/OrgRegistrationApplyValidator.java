package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.context.OrgRegistrationCreateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationParticipantRequest;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategoryCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategorySouvenirCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class OrgRegistrationApplyValidator
        extends RegistrationApplyValidator {

    public OrgRegistrationApplyValidator(
            RegistrationCommandRepository registrationCommandRepository,
            EventCommandRepository eventCommandRepository,
            EventCategoryCommandRepository eventCategoryCommandRepository,
            EventCategorySouvenirCommandRepository eventCategorySouvenirCommandRepository
    ) {

        super(
                registrationCommandRepository,
                eventCommandRepository,
                eventCategoryCommandRepository,
                eventCategorySouvenirCommandRepository
        );
    }


    public OrgRegistrationCreateContext validate(
            String eventId,
            OrgRegistrationCreateRequest request
    ) {

        Event event =
                getEvent(
                        eventId
                );


        validateEvent(
                event
        );


        /*
         * 같은 단체 신청 Request 내부에
         * 동일 참가자가 두 번 들어오는 경우를 먼저 차단한다.
         *
         * DB exists 검증만으로는 아직 INSERT되지 않은
         * 동일 Request 내부 중복을 잡을 수 없다.
         */
        validateDuplicateParticipants(
                request.registrations()
        );


        List<OrgRegistrationCreateContext.ParticipantContext> registrationContexts =
                new ArrayList<>(
                        request.registrations().size()
                );


        for (
                OrgRegistrationParticipantRequest registrationRequest
                : request.registrations()
        ) {

            /*
             * 이미 동일 대회에 신청된 참가자인지 검증.
             */
            validateAlreadyRegisteredParticipant(
                    eventId,
                    registrationRequest.name(),
                    registrationRequest.phNum(),
                    registrationRequest.birth()
            );


            EventCategory eventCategory =
                    getEventCategory(
                            registrationRequest.eventCategoryId()
                    );


            validateCategory(
                    event,
                    eventCategory
            );


            List<SouvenirJson> souvenirJsons =
                    validateSouvenirs(
                            event,
                            eventCategory,
                            registrationRequest.selectedSouvenirList()
                    );


            registrationContexts.add(
                    new OrgRegistrationCreateContext.ParticipantContext(
                            registrationRequest,
                            eventCategory,
                            souvenirJsons
                    )
            );
        }


        return new OrgRegistrationCreateContext(
                event,
                List.copyOf(
                        registrationContexts
                )
        );
    }


    /**
     * 하나의 단체 신청 Request 안에
     * 동일한 참가자 정보가 두 번 이상 들어오는 것을 방지한다.
     *
     * DB exists 검증은 아직 저장되지 않은 같은 Request 내부 중복을
     * 확인할 수 없으므로 별도로 필요하다.
     */
    protected void validateDuplicateParticipants(
            List<OrgRegistrationParticipantRequest> registrations
    ) {

        Set<ParticipantUniqueInfo> uniqueInfos =
                new HashSet<>();


        for (
                OrgRegistrationParticipantRequest registration
                : registrations
        ) {

            ParticipantUniqueInfo uniqueInfo =
                    new ParticipantUniqueInfo(
                            registration.name(),
                            registration.phNum(),
                            registration.birth()
                    );


            if (
                    !uniqueInfos.add(
                            uniqueInfo
                    )
            ) {

                throw new CustomException(
                        ErrorCode.REGISTRATION_ALREADY_EXISTS
                );
            }
        }
    }


    private record ParticipantUniqueInfo(
            String name,
            String phNum,
            String birth
    ) {
    }
}