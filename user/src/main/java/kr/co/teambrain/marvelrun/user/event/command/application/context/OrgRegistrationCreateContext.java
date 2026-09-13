package kr.co.teambrain.marvelrun.user.event.command.application.context;

import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationParticipantRequest;

import java.util.List;

public record OrgRegistrationCreateContext(

        Event event,

        List<ParticipantContext> registrations
) {

    public record ParticipantContext(

            OrgRegistrationParticipantRequest request,

            EventCategory eventCategory,

            List<SouvenirJson> souvenirJsons
    ) {
    }
}
