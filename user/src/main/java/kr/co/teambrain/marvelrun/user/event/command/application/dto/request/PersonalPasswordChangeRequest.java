package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.PersonalRegistrationPassword;

/** 개인 신청의 기존 비밀번호와 생성 정책을 만족하는 신규 비밀번호를 받는다. */
public record PersonalPasswordChangeRequest(

        @NotBlank
        String currentPassword,

        @PersonalRegistrationPassword
        String newPassword

) {
}
