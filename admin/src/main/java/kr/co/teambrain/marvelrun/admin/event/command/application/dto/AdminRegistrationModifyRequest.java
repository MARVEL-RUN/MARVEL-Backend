package kr.co.teambrain.marvelrun.admin.event.command.application.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;

/**
 * 관리자 서버 신청자 기본 정보 수정 요청 DTO
 */
public record AdminRegistrationModifyRequest(
        @NotBlank(message = "이름을 입력해주세요.")
        String name,

        @NotBlank(message = "연락처를 입력해주세요.")
        @Pattern(regexp = "^[0-9]+$", message = "연락처는 '-' 없이 숫자만 입력해주세요.")
        String phNum,

        @NotBlank(message = "생년월일을 입력해주세요.")
        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "생년월일은 'YYYY-MM-DD' 형식(예: 2000-11-11)으로 입력해주세요.")
        String birth,

        @NotNull(message = "성별을 선택해주세요.")
        GenderClass gender,

        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email,

        String address,
        String addressDetail,

        String guardianName,

        @Pattern(regexp = "^[0-9]*$", message = "보호자 연락처는 '-' 없이 숫자만 입력해주세요.")
        String guardianPhNum,

        String guardianRelationship
) {
}