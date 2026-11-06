package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.RegistrationCapacityService;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.ParticipantAdditionalOrderRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationEntryRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationItemRequest;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 추가 주문이 대표 주문의 신원·이메일·비밀번호 해시·주문자 키를 복사해 추가 주문 경로로 만드는지 본다. */
@ExtendWith(MockitoExtension.class)
class ParticipantAdditionalOrderServiceTest {

    @Mock private RegistrationCommandRepository registrationRepository;
    @Mock private RegistrationCapacityService registrationCapacityService;
    @Mock private RegistrationCommandService registrationCommandService;
    @InjectMocks private ParticipantAdditionalOrderService service;

    @Test
    void 대표_주문의_신원과_해시와_주문자_키를_복사한다() {
        Registration source = Registration.builder().id("r1").ordererKey("orderer")
                .event(Event.builder().id("event").build())
                .name("홍길동").phNum("01011112222").birth("1990-01-01").gender(GenderClass.M)
                .email("hong@example.com").password("$2a$hash").build();
        when(registrationRepository.findEventIdByOrdererKey("orderer")).thenReturn(Optional.of("event"));
        when(registrationRepository.findOrdererRegistrationsForUpdate("orderer")).thenReturn(List.of(source));

        service.order("orderer", request());

        ArgumentCaptor<RegistrationCreateRequest> created = ArgumentCaptor.forClass(RegistrationCreateRequest.class);
        verify(registrationCapacityService).lockEvent("event");
        verify(registrationCommandService).registerAdditional(eq("event"), created.capture(), eq("$2a$hash"), eq("orderer"));
        RegistrationCreateRequest request = created.getValue();
        assertThat(request.name()).isEqualTo("홍길동");
        assertThat(request.phNum()).isEqualTo("01011112222");
        assertThat(request.birth()).isEqualTo("1990-01-01");
        assertThat(request.gender()).isEqualTo(GenderClass.M);
        assertThat(request.email()).isEqualTo("hong@example.com");
        assertThat(request.password()).isNull();
        assertThat(request.items()).hasSize(1);
        assertThat(request.address()).isEqualTo("서울");
    }

    @Test
    void 주문자의_주문이_없으면_거절한다() {
        when(registrationRepository.findEventIdByOrdererKey("orderer")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.order("orderer", request()))
                .isInstanceOfSatisfying(CustomException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.REGISTRATION_ACCESS_DENIED));
        verify(registrationCommandService, never()).registerAdditional(any(), any(), any(), any());
    }

    private static ParticipantAdditionalOrderRequest request() {
        RegistrationItemRequest item = new RegistrationItemRequest("vr-avengers",
                List.of(new SouvenirJson("vr-kit", "FREE")),
                List.of(new RegistrationEntryRequest("vr-avengers", "김아이", "01033334444", "2015-05-01", GenderClass.M)));
        return new ParticipantAdditionalOrderRequest(List.of(item), "서울", "1층", false, null, null, null,
                true, false, false, null, true, null);
    }
}
