package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Objects;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
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
/** 신청자의 그룹 선택과 미선택 여부를 포함하는 개인 신청 요청이다. */
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

        boolean guardianConsent,
        @Size(max = 50)
        String guardianName,
        @Size(max = 14)
        String guardianPhNum,
        @Size(max = 50)
        String guardianRelationship,

        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email,
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

    /** 서버가 종목별로 검증한 실제 그룹 ID를 반영한 요청을 반환한다. */
    public RegistrationModificationRequest withResolvedStartGroupId(String resolvedId) {
        if (Objects.equals(startGroupId, resolvedId)) { return this; }
        return new RegistrationModificationRequest(access, eventCategoryId, selectedSouvenirList,
                name, phNum, birth, gender, address, addressDetail, guardianConsent, guardianName,
                guardianPhNum, guardianRelationship, email, resolvedId, startGroupUnselected);
    }
}
