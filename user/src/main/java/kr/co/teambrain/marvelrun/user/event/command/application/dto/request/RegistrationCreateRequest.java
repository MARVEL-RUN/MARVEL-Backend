package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;

public record RegistrationCreateRequest(

        @NotBlank
        String eventCategoryId,

        @NotBlank
        @Size(max = 127)
        String password,

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
        GenderClass gender,

        @Size(max = 300)
        String address,

        String addressDetail

) {
}