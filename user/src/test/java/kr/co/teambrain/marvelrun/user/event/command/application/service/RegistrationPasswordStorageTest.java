package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.RegistrationCapacityService;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.ReservationReleaseService;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationCreateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.context.OrgRegistrationCreateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.*;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.*;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.*;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationApplyValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.OrgRegistrationApplyValidator;
import kr.co.teambrain.marvelrun.user.event.command.repository.*;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentAllocationCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 생성 서비스를 통과한 저장값이 해시이며 요청 DTO의 원문은 유지되는지 확인한다. */
@ExtendWith(MockitoExtension.class)
class RegistrationPasswordStorageTest {
    @Spy private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    @Spy private OrgParticipantPasswordEncoder participantPasswordEncoder =
            new OrgParticipantPasswordEncoder(new BCryptPasswordEncoder(4));
    @Mock private RegistrationCapacityService capacity;
    @Mock private ReservationReleaseService release;
    @Mock private EventCommandRepository events;
    @Mock private EventCategoryCommandRepository categories;
    @Mock private RegistrationCommandRepository registrations;
    @Mock private OrganizationCommandRepository organizations;
    @Mock private RegistrationApplyValidator personalValidator;
    @Mock private OrgRegistrationApplyValidator organizationValidator;
    @Mock private RegistrationPricingService pricing;
    @Mock private PaymentCreator payments;
    @Mock private PaymentAllocationCreator allocations;
    @Mock private ServerTimeProvider time;
    @Mock private PaymentCommandRepository paymentRepository;
    @Mock private PaymentAllocationCommandRepository allocationRepository;
    @InjectMocks private RegistrationCommandService personalService;
    @InjectMocks private OrgRegistrationCommandService organizationService;
    private final Event event = Event.builder().id("event").build();
    private final EventCategory category = EventCategory.builder().id("category").build();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 27, 12, 0);

    /** 개인 신청 저장에는 서비스에서 만든 해시가 전달되고 입력 record는 유지된다. */
    @Test
    void storesPersonalHashWithoutChangingRequest() {
        RegistrationCreateRequest request = personalRequest("TestPassword1!");
        preparePersonal(request);
        when(registrations.save(any(Registration.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(payments.createInitialPayment(any(Registration.class), anyString())).thenReturn(mock(Payment.class));

        personalService.register("event", request);

        ArgumentCaptor<Registration> saved = ArgumentCaptor.forClass(Registration.class);
        verify(registrations).save(saved.capture());
        assertThat(saved.getValue().getPassword()).isNotEqualTo(request.password());
        assertThat(passwordEncoder.matches(request.password(), saved.getValue().getPassword())).isTrue();
        assertThat(request.password()).isEqualTo("TestPassword1!");
    }

    /** 단체 계정은 구성원 생성과 별도로 계정 비밀번호 해시를 저장한다. */
    @Test
    void storesOrganizationHashWithoutChangingRequest() {
        OrgRegistrationCreateRequest request = organizationRequest("TestPassword1!");
        prepareOrganization(request);
        when(organizations.save(any(Organization.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(registrations.saveAll(anyList())).thenReturn(List.of());
        when(payments.createInitialPayment(any(Organization.class), any(BigDecimal.class), anyString()))
                .thenReturn(mock(Payment.class));

        organizationService.register("event", request);

        ArgumentCaptor<Organization> saved = ArgumentCaptor.forClass(Organization.class);
        verify(organizations).save(saved.capture());
        assertThat(saved.getValue().getPassword()).isNotEqualTo(request.account().organizationPassword());
        assertThat(passwordEncoder.matches(request.account().organizationPassword(), saved.getValue().getPassword())).isTrue();
        assertThat(request.account().organizationPassword()).isEqualTo("TestPassword1!");
    }

    /** 최초 단체 생성에서도 참가자마다 고정 원문의 서로 다른 해시를 저장한다. */
    @Test
    void storesDistinctHashesForInitialOrganizationParticipants() {
        OrgRegistrationCreateRequest request = organizationRequest("TestPassword1!");
        OrgRegistrationParticipantRequest participant = new OrgRegistrationParticipantRequest(
                "category", List.of(), "참가자", "010-0000-0000", "1990-01-01", GenderClass.M);
        OrgRegistrationCreateContext.ParticipantContext context =
                new OrgRegistrationCreateContext.ParticipantContext(participant, category, List.of());
        OrgRegistrationParticipantRequest secondParticipant = new OrgRegistrationParticipantRequest(
                "category", List.of(), "다른 참가자", "010-0000-0001", "1990-01-01", GenderClass.M);
        OrgRegistrationCreateContext.ParticipantContext secondContext =
                new OrgRegistrationCreateContext.ParticipantContext(secondParticipant, category, List.of());
        when(time.currentDateTime()).thenReturn(NOW);
        when(organizationValidator.validate("event", request, NOW))
                .thenReturn(new OrgRegistrationCreateContext(event, List.of(context, secondContext)));
        when(pricing.calculateContractAmount(event, category, participant.birth())).thenReturn(BigDecimal.TEN);
        when(organizations.save(any(Organization.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(registrations.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(payments.createInitialPayment(any(Organization.class), any(BigDecimal.class), anyString()))
                .thenReturn(mock(Payment.class));

        organizationService.register("event", request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Registration>> saved = ArgumentCaptor.forClass(List.class);
        verify(registrations).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(2);
        assertThat(saved.getValue()).allSatisfy(registration -> {
            assertThat(passwordEncoder.matches("%^MVP_ORG_T&*EM^&#P_PA$%SSWO@!RD",
                    registration.getPassword())).isTrue();
            assertThat(passwordEncoder.matches(request.account().organizationPassword(),
                    registration.getPassword())).isFalse();
        });
        assertThat(saved.getValue().get(0).getPassword()).isNotEqualTo(saved.getValue().get(1).getPassword());
        verify(participantPasswordEncoder, times(2)).encode();
    }

    /** 문자 수가 짧아도 UTF-8 한도를 넘는 입력은 개인·단체 모두 저장 전에 거부한다. */
    @ParameterizedTest
    @ValueSource(strings = {"ascii", "korean"})
    void rejectsOversizedPasswordsBeforeSaving(String kind) {
        String raw = kind.equals("ascii") ? "a".repeat(73) : "가".repeat(25);
        RegistrationCreateRequest personal = personalRequest(raw);
        OrgRegistrationCreateRequest organization = organizationRequest(raw);
        preparePersonal(personal);
        prepareOrganization(organization);

        assertThatThrownBy(() -> personalService.register("event", personal))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REGISTRATION_PASSWORD_TOO_LONG));
        assertThatThrownBy(() -> organizationService.register("event", organization))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.REGISTRATION_PASSWORD_TOO_LONG));
        verify(registrations, never()).save(any());
        verify(organizations, never()).save(any());
        verify(passwordEncoder, never()).encode(any());
    }

    /** 해시 저장 경계에 집중하도록 기존 개인 신청 정책 검증 결과를 준비한다. */
    private void preparePersonal(RegistrationCreateRequest request) {
        when(time.currentDateTime()).thenReturn(NOW);
        when(personalValidator.validate("event", request, NOW))
                .thenReturn(new RegistrationCreateContext(event, category, List.of()));
        when(pricing.calculateContractAmount(event, category, request.birth())).thenReturn(BigDecimal.TEN);
    }

    /** 구성원 정책은 기존 검증기에 맡기고 단체 계정의 저장 경계를 준비한다. */
    private void prepareOrganization(OrgRegistrationCreateRequest request) {
        when(time.currentDateTime()).thenReturn(NOW);
        when(organizationValidator.validate("event", request, NOW))
                .thenReturn(new OrgRegistrationCreateContext(event, List.of()));
    }

    /** 원문 비밀번호를 담은 개인 생성 요청을 만든다. */
    private RegistrationCreateRequest personalRequest(String password) {
        return new RegistrationCreateRequest("category", List.of(), password, "참가자", "010-0000-0000",
                "1990-01-01", GenderClass.M, "주소", "상세", false, null, null, null,
                true, false, false, "test@example.com");
    }

    /** 원문 비밀번호를 담은 단체 생성 요청을 만든다. */
    private OrgRegistrationCreateRequest organizationRequest(String password) {
        return new OrgRegistrationCreateRequest(new OrgAccountRequest("단체", "group-login", password),
                new OrgProfileRequest("주소", "상세", LocalDate.of(1990, 1, 1), "010-0000-0000",
                        "test@example.com", "단체장", false), List.of(), true, false, false);
    }
}
