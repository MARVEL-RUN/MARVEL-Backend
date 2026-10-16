package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 개인 신청의 비밀번호와 그룹 선택만 전달하는 전용 수정 요청이다. */
public record RegistrationStartGroupModificationRequest(
        @NotBlank String password,
        String startGroupId,
        @NotNull Boolean startGroupUnselected
) {
    /** 미선택이 아니면 그룹 식별자가 필요하며 미선택이면 전달 ID는 무시한다. */
    @JsonIgnore
    @AssertTrue(message = "그룹을 선택하거나 미선택을 체크해주세요.")
    public boolean isStartGroupSelectionValid() {
        return startGroupUnselected == null || startGroupUnselected
                || (startGroupId != null && !startGroupId.isBlank());
    }
}
