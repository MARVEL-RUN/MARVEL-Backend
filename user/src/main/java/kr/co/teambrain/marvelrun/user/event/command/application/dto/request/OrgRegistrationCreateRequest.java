package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgAccountRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgProfileRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationParticipantRequest;

import java.util.List;

public record OrgRegistrationCreateRequest(

        @NotNull
        @Valid
        OrgAccountRequest account,

        @NotNull
        @Valid
        OrgProfileRequest profile,

        @NotEmpty
        List<@Valid OrgRegistrationParticipantRequest> registrations,

        @NotNull(message = "필수 약관 동의 여부가 누락되었습니다.")
        Boolean termsEssentialAgreed,

        @NotNull(message = "마케팅 활용 동의 여부가 누락되었습니다.")
        Boolean termsMarketingAgreed,

        @NotNull(message = "전자적 전송매체 수신 동의 여부가 누락되었습니다.")
        Boolean termsMarketingChannelAgreed
) {
}
