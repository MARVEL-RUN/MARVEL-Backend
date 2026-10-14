package kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import java.util.List;

/** 신청자의 그룹 선택과 미선택 여부를 포함하는 단체 참가자 요청이다. */
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
        GenderClass gender,
        String startGroupId,
        @NotNull Boolean startGroupUnselected
) {
    /** 미선택이 아니면 선택 그룹의 식별자를 반드시 전달한다. */
    @JsonIgnore
    @AssertTrue(message = "그룹을 선택하거나 미선택을 체크해주세요.")
    public boolean isStartGroupSelectionValid() {
        return startGroupUnselected == null || startGroupUnselected
                || (startGroupId != null && !startGroupId.isBlank());
    }
}
