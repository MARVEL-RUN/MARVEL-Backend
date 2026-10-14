package kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Objects;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;

import java.util.List;

/**
 * 단체 수정 요청의 최종 구성원 한 명을 표현한다.
 *
 * registrationId가 존재하면 기존 구성원,
 * null이면 신규 참가자 후보로 해석한다.
 */
/** 신청자의 그룹 선택과 미선택 여부를 포함하는 단체 참가자 요청이다. */
public record OrgRegistrationModificationParticipantRequest(

        String registrationId,

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

    /** 서버가 종목별로 검증한 실제 그룹 ID를 반영한 요청을 반환한다. */
    public OrgRegistrationModificationParticipantRequest withResolvedStartGroupId(String resolvedId) {
        if (Objects.equals(startGroupId, resolvedId)) { return this; }
        return new OrgRegistrationModificationParticipantRequest(registrationId, eventCategoryId, selectedSouvenirList, name, phNum, birth, gender,
                resolvedId, startGroupUnselected);
    }
}
