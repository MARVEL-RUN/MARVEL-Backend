package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationModificationParticipantRequest;

import java.time.LocalDate;
import java.util.List;

/**
 * 단체 신청 수정 요청이다.
 *
 * access는 이전 조회의 인증상태를 나타내는 값이 아니라,
 * 이 수정 요청 자체에서 Organization 소유권을
 * 다시 검증하기 위한 입력값이다.
 *
 * 서버는 별도의 수정 권한 Token을 발급하거나 보존하지 않는다.
 *
 * registrations는 변경된 참가자만이 아니라
 * 수정 완료 후 남아 있어야 할 전체 구성원 목록이다.
 */
public record OrgRegistrationModificationRequest(
        boolean guardianConsent,

        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 30)
        String email,

        @NotBlank
        String address,

        String addressDetail,

        @NotBlank
        String leaderName,

        @NotNull
        @Past(message = "생년월일은 과거여야 합니다.")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate leaderBirth,

        @NotBlank
        @Pattern(
                regexp = "^(?:010-\\d{4}-\\d{4}|02-\\d{3,4}-\\d{4}|0(?:3[1-3]|4[1-4]|5[1-5]|6[1-4])-\\d{3,4}-\\d{4})$",
                message = "연락처 형식이 올바르지 않습니다."
        )
        String leaderPhNum,

        @NotNull
        @Valid
        OrganizationAccessRequest access,

        @NotEmpty
        List<@NotNull @Valid OrgRegistrationModificationParticipantRequest> registrations
) {
}