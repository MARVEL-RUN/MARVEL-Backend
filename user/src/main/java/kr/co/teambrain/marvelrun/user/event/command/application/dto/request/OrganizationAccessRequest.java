package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 단체 신청의 작업 권한을 확인하는 입력이다.
 *
 * 단체 신청 시 저장한 로그인 아이디와 비밀번호를 대조한다.
 */
public record OrganizationAccessRequest(
        @NotBlank String loginId,
        @NotBlank String password
) {
}