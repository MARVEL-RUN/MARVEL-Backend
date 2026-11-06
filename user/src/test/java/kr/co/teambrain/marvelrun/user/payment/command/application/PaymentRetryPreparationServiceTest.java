package kr.co.teambrain.marvelrun.user.payment.command.application;

import jakarta.persistence.EntityManager;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 로그인한 주문자의 결제 재시도가 남의 주문을 결제·대회 행을 잠그기 전에 거절하는지 본다. */
@ExtendWith(MockitoExtension.class)
class PaymentRetryPreparationServiceTest {

    @Mock private PaymentConfirmationAllocationSupport support;
    @Mock private EntityManager entityManager;
    @InjectMocks private PaymentRetryPreparationService service;

    @Test
    void 남의_주문은_결제를_잠그기_전에_거절한다() {
        Registration registration = Registration.builder().id("r1").ordererKey("orderer")
                .event(Event.builder().id("event").build()).build();
        when(entityManager.find(Registration.class, "r1")).thenReturn(registration);

        assertDenied(() -> service.prepareOwnedPersonal("r1", "p1", "other"));
        verifyNoInteractions(support);
    }

    @Test
    void 없는_주문은_남의_주문과_같은_오류로_거절한다() {
        assertDenied(() -> service.prepareOwnedPersonal("missing", "p1", "orderer"));
        verifyNoInteractions(support);
    }

    private static void assertDenied(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(CustomException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.REGISTRATION_ACCESS_DENIED));
    }
}
