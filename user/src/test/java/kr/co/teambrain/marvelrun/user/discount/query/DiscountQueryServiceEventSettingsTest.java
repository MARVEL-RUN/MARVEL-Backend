package kr.co.teambrain.marvelrun.user.discount.query;

import kr.co.teambrain.marvelrun.common.inheritance_enum.EventFormat;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ParticipantAuthMethod;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ParticipantCredentialType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ParticipantIdentifierType;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.discount.repository.DiscountLinkRepository;
import kr.co.teambrain.marvelrun.user.discount.repository.DiscountPolicyRepository;
import kr.co.teambrain.marvelrun.user.discount.service.DiscountPolicyResolver;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategoryCommandRepository;
import kr.co.teambrain.marvelrun.user.event.query.dto.response.EventSettingsResponse;
import kr.co.teambrain.marvelrun.user.event.query.dto.response.RegistrationOptionResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 신청 선택지에 내부 할인 안내를 덧붙여 응답을 다시 만들 때 대회 설정을 잃지 않는지 확인한다. */
class DiscountQueryServiceEventSettingsTest {

    @Test
    void appendingPricingOptionsKeepsEventSettings() {
        DiscountQueryService service = new DiscountQueryService(mock(DiscountPolicyResolver.class),
                mock(DiscountLinkRepository.class), mock(DiscountPolicyRepository.class),
                mock(EventCategoryCommandRepository.class), mock(ServerTimeProvider.class));
        EventSettingsResponse settings = new EventSettingsResponse(EventFormat.VIRTUAL,
                new EventSettingsResponse.ParticipantAuth(ParticipantAuthMethod.BIB_PHONE_OTP,
                        ParticipantIdentifierType.BIB, ParticipantCredentialType.PHONE_OTP),
                true, false);

        RegistrationOptionResponse appended = service.appendInternalPricingOptions("event",
                new RegistrationOptionResponse(List.of(), true, null, settings));

        assertThat(appended.eventSettings()).isEqualTo(settings);
    }
}
