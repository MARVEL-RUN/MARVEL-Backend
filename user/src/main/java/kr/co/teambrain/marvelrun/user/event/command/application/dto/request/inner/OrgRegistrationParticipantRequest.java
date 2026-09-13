package kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;

import java.util.List;

public record OrgRegistrationParticipantRequest(

        @NotBlank
        String eventCategoryId,

        @NotEmpty
        List<@Valid SouvenirJson> selectedSouvenirList,

        @NotBlank
        String name,

        @NotBlank
        String phNum,

        @NotBlank
        String birth,

        @NotNull
        GenderClass gender
) {
}
