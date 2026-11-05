package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 개인 수정의 신원 검사. 같은 신청자 주문 여러 건 허용 대회의 신청은 DB 유니크 인덱스가 보지 않으므로
 * 신원이 그대로면 검사하지 않고, 바뀔 때만 대회 행을 잠근 뒤 최신 행을 잠금 읽기로 찾는다.
 */
@ExtendWith(MockitoExtension.class)
class RegistrationUniqueInfoValidatorTest {

    @Mock private RegistrationCommandRepository registrations;
    @Mock private EventCommandRepository events;

    private final Event event = Event.builder().id("event").build();

    /** 허용하지 않는 대회(오프라인 등)는 지금처럼 항상 다른 활성 신청을 검사하고 대회를 잠그지 않는다. */
    @Test
    void notDuplicateAllowedAlwaysChecksWithoutLock() {
        Registration current = registration(false);
        when(registrations.existsOtherActiveByEventIdAndUniqueInfo("event", "r", "신청자", "010", "1990-01-01"))
                .thenReturn(true);

        assertThatThrownBy(() -> validator().validatePersonalIdentity(current, "신청자", "010", "1990-01-01"))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.REGISTRATION_ALREADY_EXISTS));
        verifyNoInteractions(events);
    }

    /** 신원이 그대로면 같은 신청자의 다른 주문이 있어도 주소·보호자 같은 수정을 막지 않는다. 잠금도 없다. */
    @Test
    void duplicateAllowedUnchangedIdentitySkipsCheckAndLock() {
        assertThatCode(() -> validator().validatePersonalIdentity(registration(true), "신청자", "010", "1990-01-01"))
                .doesNotThrowAnyException();
        verifyNoInteractions(events, registrations);
    }

    /** 신원을 바꾸면 신청 생성과 같은 대회 행 잠금을 먼저 잡고, 그 뒤 최신 행을 잠금 읽기로 센다. */
    @Test
    void duplicateAllowedChangedIdentityLocksEventBeforeCheck() {
        when(events.findByIdForUpdate("event")).thenReturn(Optional.of(event));
        when(registrations.countOtherActiveByEventIdAndUniqueInfoForShare("event", "r", "새이름", "010", "1990-01-01"))
                .thenReturn(0L);

        validator().validatePersonalIdentity(registration(true), "새이름", "010", "1990-01-01");

        InOrder order = inOrder(events, registrations);
        order.verify(events).findByIdForUpdate("event");
        order.verify(registrations).countOtherActiveByEventIdAndUniqueInfoForShare("event", "r", "새이름", "010", "1990-01-01");
        verify(registrations, never()).existsOtherActiveByEventIdAndUniqueInfo(any(), any(), any(), any(), any());
    }

    /** 바꿀 신원에 다른 활성 주문이 있으면 거절한다. 추가 주문은 로그인 흐름으로만 만든다. */
    @Test
    void duplicateAllowedChangeIntoExistingIdentityIsRejected() {
        when(events.findByIdForUpdate("event")).thenReturn(Optional.of(event));
        when(registrations.countOtherActiveByEventIdAndUniqueInfoForShare("event", "r", "신청자", "010", "2000-02-02"))
                .thenReturn(1L);

        assertThatThrownBy(() -> validator().validatePersonalIdentity(registration(true), "신청자", "010", "2000-02-02"))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.REGISTRATION_ALREADY_EXISTS));
    }

    private RegistrationUniqueInfoValidator validator() {
        return new RegistrationUniqueInfoValidator(registrations, events);
    }

    private Registration registration(boolean duplicateAllowed) {
        return Registration.builder().id("r").event(event).name("신청자").phNum("010").birth("1990-01-01")
                .duplicateAllowed(duplicateAllowed).build();
    }
}
