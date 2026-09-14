package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgAccountRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgProfileRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationParticipantRequest;

import java.util.List;

public record OrgRegistrationCreateRequest(

        @NotNull
        @Valid
        OrgAccountRequest account,

        @NotNull
        @Valid
        OrgProfileRequest profile,

        @NotEmpty
        List<@Valid OrgRegistrationParticipantRequest> registrations
) {
}
