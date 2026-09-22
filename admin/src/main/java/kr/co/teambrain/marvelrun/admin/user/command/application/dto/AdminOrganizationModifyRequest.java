package kr.co.teambrain.marvelrun.admin.user.command.application.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record AdminOrganizationModifyRequest(
        @NotBlank(message = "단체명을 입력해주세요.")
        String groupName,

        @NotBlank(message = "단체 대표자명을 입력해주세요.")
        String leaderName,

        @NotBlank(message = "대표자 생년월일을 입력해주세요.")
        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "생년월일은 'YYYY-MM-DD' 형식(예: 2000-11-11)으로 입력해주세요.")
        String leaderBirth,

        @NotBlank(message = "대표자 연락처를 입력해주세요.")
        @Pattern(regexp = "^[0-9]+$", message = "연락처는 '-' 없이 숫자만 입력해주세요.")
        String leaderPhNum,

        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email,

        String address,
        String addressDetail,

        @NotNull(message = "법정대리인 동의 여부를 선택해주세요.")
        Boolean guardianConsent
) {
}