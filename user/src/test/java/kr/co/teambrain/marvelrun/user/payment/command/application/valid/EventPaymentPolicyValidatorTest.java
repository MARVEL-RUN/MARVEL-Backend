package kr.co.teambrain.marvelrun.user.payment.command.application.valid;
import java.time.LocalDateTime;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class EventPaymentPolicyValidatorTest {
    private final EventPaymentPolicyValidator validator = new EventPaymentPolicyValidator();
    @Test void additionalCanBePaidAfterInitialDeadline() {
        Event event = mock(Event.class);
        LocalDateTime now = LocalDateTime.of(2026,11,2,12,0);
        when(event.getPaymentDeadline()).thenReturn(now.minusDays(1));
        assertThatCode(() -> validator.validateForPurpose(event,now,PaymentPurpose.ADDITIONAL_PAYMENT)).doesNotThrowAnyException();
        for (PaymentPurpose purpose : new PaymentPurpose[]{PaymentPurpose.REGISTRATION_TRY,PaymentPurpose.MIXED_PAYMENT}) {
            assertThatThrownBy(() -> validator.validateForPurpose(event,now,purpose)).isInstanceOfSatisfying(CustomException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EVENT_PAYMENT_CLOSED));
        }
    }
}
