package kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record OrgAccountRequest(

        @NotBlank
        String organizationName,

        @NotBlank
        @Pattern(
                regexp = "^(?=.{5,20}$)[\\x21-\\x7E]+$",
                message = "아이디는 5~20자이며, 영문 대소문자, 숫자, 특수문자(ASCII)만 허용됩니다."
        )
        String organizationAccount,

        @NotBlank
        @Size(
                min = 6,
                max = 64,
                message = "비밀번호는 6~64자여야 합니다."
        )
        @Pattern(
                regexp = "^[A-Za-z\\d~!@#$%^&*()_+\\-={}\\[\\]\\\\|:;\"'<>,.?/]+$",
                message = "허용되지 않는 문자가 포함되어 있습니다."
        )
        String organizationPassword
) {
}
