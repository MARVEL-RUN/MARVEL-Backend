package kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner;

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
        GenderClass gender
) {
}