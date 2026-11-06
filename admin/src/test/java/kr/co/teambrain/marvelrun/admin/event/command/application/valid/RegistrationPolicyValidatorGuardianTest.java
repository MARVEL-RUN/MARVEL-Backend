package kr.co.teambrain.marvelrun.admin.event.command.application.valid;

import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.policy.EventRegistrationPolicy;
import kr.co.teambrain.marvelrun.admin.event.command.application.valid.dto.RegistrationPolicyInput;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 대회 정책의 보호자 동의 필수 여부(guardian_consent_required)에 따른 보호자 검증 */
class RegistrationPolicyValidatorGuardianTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);

    private final RegistrationPolicyValidator validator = new RegistrationPolicyValidator();

    @Test
    void 보호자_동의를_받는_대회는_기준일_이후_출생자에게_보호자를_요구한다() {
        Event event = event();
        EventRegistrationPolicy policy = policy(event, true);

        assertThatThrownBy(() -> validator.validateParticipant(event, policy,
                new RegistrationPolicyInput("2015-01-01", null, null), TODAY))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.GUARDIAN_NAME_REQUIRED);
    }

    @Test
    void 보호자_동의를_받지_않는_대회는_기준일_이후_출생자도_보호자_없이_통과한다() {
        Event event = event();
        EventRegistrationPolicy policy = policy(event, false);

        LocalDate birth = validator.validateParticipant(event, policy,
                new RegistrationPolicyInput("2015-01-01", null, null), TODAY);

        assertThat(birth).isEqualTo(LocalDate.of(2015, 1, 1));
    }

    @Test
    void 보호자_동의를_받지_않아도_생년월일_형식은_검사한다() {
        Event event = event();
        EventRegistrationPolicy policy = policy(event, false);

        assertThatThrownBy(() -> validator.validateParticipant(event, policy,
                new RegistrationPolicyInput("2015-13-01", null, null), TODAY))
                .isInstanceOf(CustomException.class);
    }

    private static Event event() {
        Event event = mock(Event.class);
        when(event.getId()).thenReturn("event-1");
        return event;
    }

    private static EventRegistrationPolicy policy(Event event, boolean guardianConsentRequired) {
        EventRegistrationPolicy policy = mock(EventRegistrationPolicy.class);
        when(policy.getEvent()).thenReturn(event);
        when(policy.getGuardianRequiredBirthFrom()).thenReturn(LocalDate.of(2012, 11, 6));
        lenient().when(policy.isGuardianConsentRequired()).thenReturn(guardianConsentRequired);
        return policy;
    }
}
