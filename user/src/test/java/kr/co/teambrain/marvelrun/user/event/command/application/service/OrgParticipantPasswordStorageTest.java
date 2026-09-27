package kr.co.teambrain.marvelrun.user.event.command.application.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.*;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.CapacityCommandRepository;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.application.context.OrgRegistrationModificationAccessContext;
import kr.co.teambrain.marvelrun.user.event.command.application.context.OrgRegistrationModificationCandidateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.OrgRegistrationParticipantPricing;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationPrice;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationModificationParticipantRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.OrgRegistrationModificationAccessValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.OrgRegistrationModificationCandidateValidator;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 실제 수정 서비스가 추가 참가자에게 해시를 저장하는지 DB 없이 검증한다. */
@ExtendWith(MockitoExtension.class)
class OrgParticipantPasswordStorageTest {
    @Spy private OrgParticipantPasswordEncoder participantPasswordEncoder =
            new OrgParticipantPasswordEncoder(new BCryptPasswordEncoder(4));
    @Mock private RegistrationCapacityService capacity;
    @Mock private OrgRegistrationModificationAccessValidator accessValidator;
    @Mock private OrgRegistrationModificationCandidateValidator candidateValidator;
    @Mock private RegistrationModificationPricingService pricing;
    @Mock private RegistrationCommandRepository registrations;
    @Mock private ReservationCommandRepository reservations;
    @Mock private CapacityCommandRepository capacities;
    @Mock private CapacityHoldService hold;
    @Mock private CapacityRequirementResolver requirements;
    @Mock private ReservationCapacityDiffService diff;
    @Mock private CapacityModificationService modification;
    @Mock private ReservationRemovalService removal;
    @Mock private OrgRegistrationModificationGuard guard;
    @Mock private RegistrationModificationPaymentGuard paymentGuard;
    @InjectMocks private OrgRegistrationModificationService service;

    /** 기존 단체원을 교체하는 수정에서도 새 참가자만 해시를 생성하고 기존 저장값은 보존한다. */
    @Test
    void storesHashesForAddedParticipantsWithoutRehashingExistingPassword() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 28, 12, 0);
        Event event = Event.builder().id("event").startDate(now.plusDays(30)).build();
        EventCategory category = EventCategory.builder().id("category").build();
        Organization organization = mock(Organization.class);
        Registration existing = Registration.builder().id("existing").organization(organization)
                .password("existing-stored-value").contractAmount(BigDecimal.TEN).paidAmount(BigDecimal.ZERO)
                .termsEssentialAgreed(true).build();
        OrgRegistrationModificationRequest request = mock(OrgRegistrationModificationRequest.class);
        when(request.leaderBirth()).thenReturn(LocalDate.of(1990, 1, 1));
        OrgRegistrationModificationAccessContext access = mock(OrgRegistrationModificationAccessContext.class);
        when(access.organization()).thenReturn(organization);
        OrgRegistrationModificationParticipantRequest participant = new OrgRegistrationModificationParticipantRequest(
                null, "category", List.of(), "추가 참가자", "010-0000-0000", "1990-01-01", GenderClass.M);
        OrgRegistrationModificationCandidateContext.ParticipantCandidate added =
                new OrgRegistrationModificationCandidateContext.ParticipantCandidate(null, participant, category, List.of());
        OrgRegistrationModificationParticipantRequest secondParticipant = new OrgRegistrationModificationParticipantRequest(
                null, "category", List.of(), "다른 추가 참가자", "010-0000-0001", "1990-01-01", GenderClass.M);
        OrgRegistrationModificationCandidateContext.ParticipantCandidate secondAdded =
                new OrgRegistrationModificationCandidateContext.ParticipantCandidate(null, secondParticipant, category, List.of());
        OrgRegistrationModificationCandidateContext candidate = new OrgRegistrationModificationCandidateContext(
                event, organization, List.of(existing), List.of(added, secondAdded), request, now);
        when(candidateValidator.validate(access)).thenReturn(candidate);
        OrgRegistrationParticipantPricing price = new OrgRegistrationParticipantPricing(
                added, RegistrationModificationPrice.forNew(BigDecimal.TEN));
        OrgRegistrationParticipantPricing secondPrice = new OrgRegistrationParticipantPricing(
                secondAdded, RegistrationModificationPrice.forNew(BigDecimal.TEN));
        when(pricing.repriceOrganization(candidate)).thenReturn(List.of(price, secondPrice));
        when(registrations.save(any(Registration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.modify("event", "organization", request, now, access);

        ArgumentCaptor<Registration> saved = ArgumentCaptor.forClass(Registration.class);
        verify(registrations, times(2)).save(saved.capture());
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        assertThat(saved.getAllValues()).allSatisfy(registration -> {
            assertThat(encoder.matches("%^MVP_ORG_T&*EM^&#P_PA$%SSWO@!RD", registration.getPassword())).isTrue();
            assertThat(registration.getOrganization()).isSameAs(organization);
        });
        assertThat(saved.getAllValues().get(0).getPassword()).isNotEqualTo(saved.getAllValues().get(1).getPassword());
        assertThat(existing.getPassword()).isEqualTo("existing-stored-value");
        verify(participantPasswordEncoder, times(2)).encode();
    }
}
