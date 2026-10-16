package kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner;

import io.swagger.v3.oas.annotations.media.Schema;
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
        // 그룹 기능 임시 중단: 내부 호환 필드는 JSON·Swagger에서 제외한다.
        @JsonIgnore @Schema(hidden = true)
        String startGroupId,
        // 그룹 기능 임시 중단: 내부 호환 필드는 JSON·Swagger에서 제외한다.
        @JsonIgnore @Schema(hidden = true)
        Boolean startGroupUnselected
) {
    /** 미선택이 아니면 선택 그룹의 식별자를 반드시 전달한다. */
    @JsonIgnore @Schema(hidden = true)
    // 그룹 기능 임시 중단: 재활성화 시 조합 검증 복원.
    // @AssertTrue(message = "그룹을 선택하거나 미선택을 체크해주세요.")
    public boolean isStartGroupSelectionValid() {
        return startGroupUnselected == null || startGroupUnselected
                || (startGroupId != null && !startGroupId.isBlank());
    }
}
