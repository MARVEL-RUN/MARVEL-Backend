package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.OrganizationRegistrationPassword;

/** 단체 계정의 기존 비밀번호와 생성 정책을 만족하는 신규 비밀번호를 받는다. */
public record OrganizationPasswordChangeRequest(

        @NotBlank
        String currentPassword,

        @OrganizationRegistrationPassword
        String newPassword

) {
}
