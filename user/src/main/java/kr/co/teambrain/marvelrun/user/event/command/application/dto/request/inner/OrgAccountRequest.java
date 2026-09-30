package kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.OrganizationRegistrationPassword;

/** 단체 계정 생성 입력이며 비밀번호 정책은 변경 요청과 공유한다. */
public record OrgAccountRequest(

        @NotBlank
        String organizationName,

        @NotBlank
        @Pattern(
                regexp = "^(?=.{5,20}$)[\\x21-\\x7E]+$",
                message = "아이디는 5~20자이며, 영문 대소문자, 숫자, 특수문자(ASCII)만 허용됩니다."
        )
        String organizationLoginId,

        @OrganizationRegistrationPassword
        String organizationPassword
) {
}
