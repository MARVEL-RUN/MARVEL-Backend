package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgAccountRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgProfileRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationParticipantRequest;

import java.util.List;

/** 단체 최초 신청은 최소 2명이며 할인 링크 코드는 선택 입력이다. */
public record OrgRegistrationCreateRequest(

        @NotNull
        @Valid
        OrgAccountRequest account,

        @NotNull
        @Valid
        OrgProfileRequest profile,

        @NotEmpty
        @Size(min = 2)
        List<@Valid OrgRegistrationParticipantRequest> registrations,

        @NotNull(message = "필수 약관 동의 여부가 누락되었습니다.")
        Boolean termsEssentialAgreed,

        @NotNull(message = "마케팅 활용 동의 여부가 누락되었습니다.")
        Boolean termsMarketingAgreed,

        @NotNull(message = "전자적 전송매체 수신 동의 여부가 누락되었습니다.")
        Boolean termsMarketingChannelAgreed,
        @Size(max = 64)
        String discountLinkCode
) {
    /** 기존 내부 호출부의 일반 신청 입력을 호환한다. */
    public OrgRegistrationCreateRequest(OrgAccountRequest account, OrgProfileRequest profile, List<@Valid OrgRegistrationParticipantRequest> registrations, Boolean termsEssentialAgreed, Boolean termsMarketingAgreed, Boolean termsMarketingChannelAgreed) {
        this(account, profile, registrations, termsEssentialAgreed, termsMarketingAgreed, termsMarketingChannelAgreed, null);
    }

}
