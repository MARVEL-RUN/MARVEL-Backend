package kr.co.teambrain.marvelrun.user.event.command.application.service;

import jakarta.persistence.EntityManager;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentCancelCommandRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 로그인한 주문자의 취소가 잠금 뒤 다시 읽은 행으로 남의 주문을 거절하는지 본다. */
@ExtendWith(MockitoExtension.class)
class RegistrationCancellationTransactionServiceTest {

    @Mock private EntityManager entityManager;
    @Mock private EventCommandRepository events;
    @Mock private RegistrationModificationPaymentGuard paymentGuard;
    @Mock private RegistrationModificationSettlementService settlement;
    @Mock private PaymentCancelCommandRepository cancellations;
    @InjectMocks private RegistrationCancellationTransactionService service;

    @Test
    void 남의_주문은_취소하지_않는다() {
        Event event = Event.builder().id("event").build();
        when(events.findByIdForUpdate("event")).thenReturn(Optional.of(event));
        when(paymentGuard.lockPersonal("event", "r1")).thenReturn(List.of());
        when(entityManager.find(Registration.class, "r1"))
                .thenReturn(Registration.builder().id("r1").ordererKey("orderer").event(event).build());

        assertThatThrownBy(() -> service.cancelOwnedPersonal("event", "r1", "other"))
                .isInstanceOfSatisfying(CustomException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.REGISTRATION_ACCESS_DENIED));
        verifyNoInteractions(settlement, cancellations);
    }
}
