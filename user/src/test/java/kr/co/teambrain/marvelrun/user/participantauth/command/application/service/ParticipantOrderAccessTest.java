package kr.co.teambrain.marvelrun.user.participantauth.command.application.service;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 경로로 받은 주문이 토큰 주문자의 것인지와 me 경로가 가리키는 주문을 본다. */
@ExtendWith(MockitoExtension.class)
class ParticipantOrderAccessTest {

    @Mock private RegistrationCommandRepository registrationRepository;
    @InjectMocks private ParticipantOrderAccess access;

    @Test
    void 같은_주문자의_개인_주문만_통과한다() {
        assertThat(order("r1", "orderer", false).isOwnedBy("orderer")).isTrue();

        assertThat(order("r1", "other", false).isOwnedBy("orderer")).isFalse();
        assertThat(order("r1", null, false).isOwnedBy("orderer")).isFalse();
        assertThat(order("r1", "orderer", false).isOwnedBy(null)).isFalse();
        Registration organizationMember = Registration.builder().id("r1").ordererKey("orderer")
                .organization(mock(Organization.class)).build();
        assertThat(organizationMember.isOwnedBy("orderer")).isFalse();
    }

    @Test
    void 없는_주문은_남의_주문과_같은_오류로_거절한다() {
        when(registrationRepository.findById("missing")).thenReturn(Optional.empty());
        when(registrationRepository.findById("r1")).thenReturn(Optional.of(order("r1", "other", false)));

        assertDenied(() -> access.findOwned("missing", "orderer"));
        assertDenied(() -> access.findOwned("r1", "orderer"));
    }

    @Test
    void me는_살아_있는_주문이_하나일_때만_그_주문을_가리킨다() {
        when(registrationRepository.findOrdererRegistrations("one"))
                .thenReturn(List.of(order("live", "one", false), order("canceled", "one", true)));
        when(registrationRepository.findOrdererRegistrations("two"))
                .thenReturn(List.of(order("a", "two", false), order("b", "two", false)));
        when(registrationRepository.findOrdererRegistrations("none"))
                .thenReturn(List.of(order("canceled", "none", true)));

        assertThat(access.resolveMe("one")).isEqualTo("live");
        assertThatThrownBy(() -> access.resolveMe("two"))
                .isInstanceOfSatisfying(CustomException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.PARTICIPANT_ORDER_SELECTION_REQUIRED));
        // 살아 있는 주문이 없으면 '여러 건' 안내가 아니라 이전처럼 접근 거부다.
        assertDenied(() -> access.resolveMe("none"));
    }

    private static Registration order(String id, String ordererKey, boolean canceled) {
        return Registration.builder().id(id).ordererKey(ordererKey).softDeleted(canceled).build();
    }

    private static void assertDenied(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(CustomException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.REGISTRATION_ACCESS_DENIED));
    }
}
