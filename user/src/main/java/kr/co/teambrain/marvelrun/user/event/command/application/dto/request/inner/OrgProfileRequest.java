package kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record OrgProfileRequest(

        @NotBlank
        String address,

        String addressDetail,

        @NotNull
        @Past(message = "생일은 과거 날짜여야 합니다.")
        @JsonFormat(
                shape = JsonFormat.Shape.STRING,
                pattern = "yyyy-MM-dd"
        )
        LocalDate birth,

        @NotBlank
        @Pattern(
                regexp = "^(?:010-\\d{4}-\\d{4}|02-\\d{3,4}-\\d{4}|0(?:3[1-3]|4[1-4]|5[1-5]|6[1-4])-\\d{3,4}-\\d{4})$",
                message = "전화번호는 하이픈 포함 한국 형식만 허용합니다."
        )
        String phNum,

        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 30)
        String email,

        @NotBlank
        String leaderName
) {
}
