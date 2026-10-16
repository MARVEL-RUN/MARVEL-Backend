package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 개인 신청의 비밀번호와 그룹 선택만 전달하는 전용 수정 요청이다. */
public record RegistrationStartGroupModificationRequest(
        @NotBlank String password,
        // 그룹 기능 임시 중단: 내부 호환 필드는 JSON·Swagger에서 제외한다.
        @JsonIgnore @Schema(hidden = true)
        String startGroupId,
        // 그룹 기능 임시 중단: 내부 호환 필드는 JSON·Swagger에서 제외한다.
        @JsonIgnore @Schema(hidden = true)
        Boolean startGroupUnselected
) {
    /** 미선택이 아니면 그룹 식별자가 필요하며 미선택이면 전달 ID는 무시한다. */
    @JsonIgnore @Schema(hidden = true)
    // 그룹 기능 임시 중단: 재활성화 시 조합 검증 복원.
    // @AssertTrue(message = "그룹을 선택하거나 미선택을 체크해주세요.")
    public boolean isStartGroupSelectionValid() {
        return startGroupUnselected == null || startGroupUnselected
                || (startGroupId != null && !startGroupId.isBlank());
    }
}
