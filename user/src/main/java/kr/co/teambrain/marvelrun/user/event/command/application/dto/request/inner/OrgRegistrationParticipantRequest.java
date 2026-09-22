package kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;

import java.util.List;

public record OrgRegistrationParticipantRequest(

        @NotBlank
        String eventCategoryId,

        @NotEmpty
        List<@NotNull @Valid SouvenirJson> selectedSouvenirList,

        @NotBlank
        @Size(max = 50)
        String name,

        @NotBlank
        @Size(max = 14)
        String phNum,

        @NotBlank
        @Size(max = 10)
        String birth,

        @NotNull
        GenderClass gender
) {
}
