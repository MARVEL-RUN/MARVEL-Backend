package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;

import java.util.List;

/**
 * 개인 Registration 수정 요청이다.
 *
 * access는 현재 신청 소유권 확인에만 사용하고,
 * 나머지 필드는 검증 이후 적용될 수정 후보값이다.
 * 보호자 동의는 수정 입력으로 받지 않고 기존 저장값을 유지한다.
 */
public record RegistrationModificationRequest(

        @NotNull
        @Valid
        RegistrationAccessRequest access,

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

        @Size(max = 300)
        String address,

        String addressDetail,

        @Size(max = 50)
        String guardianName,

        @Size(max = 14)
        String guardianPhNum
) {
}