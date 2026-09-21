package kr.co.teambrain.marvelrun.admin.common.dto.request;

import jakarta.validation.constraints.NotBlank;

public record PasswordResetRequest(
        @NotBlank(message = "새 비밀번호를 입력해주세요.")
        String newPassword
) {
}