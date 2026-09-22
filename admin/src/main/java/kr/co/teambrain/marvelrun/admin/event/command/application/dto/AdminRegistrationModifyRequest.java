package kr.co.teambrain.marvelrun.admin.event.command.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;

/**
 * 관리자 서버 신청자 기본 정보 수정 요청 DTO
 */
public record AdminRegistrationModifyRequest(
        @NotBlank(message = "이름을 입력해주세요.")
        String name,

        @NotBlank(message = "연락처를 입력해주세요.")
        String phNum,

        @NotBlank(message = "생년월일을 입력해주세요.")
        String birth,

        @NotNull(message = "성별을 선택해주세요.")
        GenderClass gender,

        String address,
        String addressDetail,
        String guardianName,
        String guardianPhNum,
        String guardianRelationship
) {
}