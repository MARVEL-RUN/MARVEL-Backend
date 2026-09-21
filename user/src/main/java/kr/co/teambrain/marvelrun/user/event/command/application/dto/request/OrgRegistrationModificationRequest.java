package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationModificationParticipantRequest;

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

        @NotNull
        @Valid
        OrganizationAccessRequest access,

        @NotEmpty
        List<@NotNull @Valid OrgRegistrationModificationParticipantRequest> registrations
) {
}
