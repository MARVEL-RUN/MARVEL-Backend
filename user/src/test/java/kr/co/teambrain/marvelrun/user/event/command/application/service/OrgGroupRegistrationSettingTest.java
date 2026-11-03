package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.user.capacity.command.application.service.RegistrationCapacityService;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.discount.service.LinkDiscountEligibilityService;
import kr.co.teambrain.marvelrun.user.discount.service.OrganizationDiscountGuard;
import kr.co.teambrain.marvelrun.user.event.command.application.context.OrgRegistrationCreateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.context.OrgRegistrationModificationAccessContext;
import kr.co.teambrain.marvelrun.user.event.command.application.context.OrgRegistrationModificationCandidateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.OrgRegistrationApplyValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.OrgRegistrationModificationCandidateValidator;
import kr.co.teambrain.marvelrun.user.event.command.repository.OrganizationCommandRepository;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 대회의 단체 신청 설정이 단체 생성과 구성원 추가만 막는지 확인한다. DB는 쓰지 않는다. */
@ExtendWith(MockitoExtension.class)
class OrgGroupRegistrationSettingTest {

    private static final String EVENT_ID = "event";
    private static final String ORGANIZATION_ID = "organization";
    private final LocalDateTime now = LocalDateTime.of(2026, 11, 4, 12, 0);

    @Mock private RegistrationCapacityService capacity;
    @Mock private ServerTimeProvider time;
    @Mock private OrgRegistrationApplyValidator applyValidator;
    @Mock private RegistrationActionPolicyService actionPolicies;
    @Mock private LinkDiscountEligibilityService discountEligibility;
    @Mock private OrganizationCommandRepository organizations;
    @Mock private OrgRegistrationModificationGuard modificationGuard;
    @Mock private RegistrationModificationPaymentGuard paymentGuard;
    @Mock private OrgRegistrationModificationCandidateValidator candidateValidator;
    @Mock private RegistrationModificationPricingService pricing;
    @Mock private OrganizationDiscountGuard discountGuard;

    @InjectMocks private OrgRegistrationCommandService creation;
    @InjectMocks private OrgRegistrationModificationService modification;

    /** 단체 신청을 끈 대회는 할인 링크 확보나 단체 저장 전에 거절한다. */
    @Test
    void registerRejectsWhenGroupRegistrationDisabled() {
        OrgRegistrationCreateRequest request = mock(OrgRegistrationCreateRequest.class);
        givenCreateContext(request, eventWithGroupRegistration(false));

        assertThatThrownBy(() -> creation.register(EVENT_ID, request))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.GROUP_REGISTRATION_DISABLED));
        verify(capacity).lockEvent(EVENT_ID);
        verifyNoInteractions(actionPolicies, discountEligibility, organizations);
    }

    /** 값이 비어 있으면 컬럼 기본값(허용)으로 보고 다음 단계인 접수 정책 검사로 넘어간다. */
    @Test
    void registerTreatsUnsetSettingAsEnabled() {
        OrgRegistrationCreateRequest request = mock(OrgRegistrationCreateRequest.class);
        Event event = eventWithGroupRegistration(null);
        givenCreateContext(request, event);
        // 다음 단계에 도달했는지만 보려고 접수 정책 검사에서 멈춘다.
        doThrow(new CustomException(ErrorCode.EVENT_NOT_OPEN)).when(actionPolicies).validateNewRegistrationPolicy(event, now);

        assertThatThrownBy(() -> creation.register(EVENT_ID, request))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EVENT_NOT_OPEN));
    }

    /** 단체 신청을 끈 대회에서 새 구성원을 더하는 수정은 가격 계산과 프로필 반영 전에 거절한다. */
    @Test
    void modificationRejectsAddingMemberWhenGroupRegistrationDisabled() {
        OrgRegistrationModificationRequest request = mock(OrgRegistrationModificationRequest.class);
        Organization organization = mock(Organization.class);
        OrgRegistrationModificationAccessContext access = givenAccess(request, organization);
        givenCandidates(access, eventWithGroupRegistration(false), member(mock(Registration.class)), member(null));

        assertThatThrownBy(() -> modification.modifyOrganizationRegistration(EVENT_ID, ORGANIZATION_ID, request, now, access))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.GROUP_REGISTRATION_DISABLED));
        verify(organization, never()).applyProfileModification(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(pricing);
    }

    /** 단체 신청을 꺼도 기존 구성원만 남기는 수정은 설정 검사를 지나 가격 계산까지 간다. */
    @Test
    void modificationKeepsExistingMembersWhenGroupRegistrationDisabled() {
        OrgRegistrationModificationRequest request = mock(OrgRegistrationModificationRequest.class);
        when(request.leaderBirth()).thenReturn(LocalDate.of(1990, 1, 1));
        OrgRegistrationModificationAccessContext access = givenAccess(request, mock(Organization.class));
        OrgRegistrationModificationCandidateContext candidate =
                givenCandidates(access, eventWithGroupRegistration(false), member(mock(Registration.class)));

        // 가격 계산 뒤 단계는 이 테스트의 관심이 아니므로 결과와 무관하게 설정 검사 통과만 본다.
        Throwable thrown = catchThrowable(
                () -> modification.modifyOrganizationRegistration(EVENT_ID, ORGANIZATION_ID, request, now, access));
        if (thrown instanceof CustomException e) {
            assertThat(e.getErrorCode()).isNotEqualTo(ErrorCode.GROUP_REGISTRATION_DISABLED);
        }
        verify(pricing).calculateOrganizationRegistrationModificationPrices(candidate);
    }

    private void givenCreateContext(OrgRegistrationCreateRequest request, Event event) {
        when(time.currentDateTime()).thenReturn(now);
        when(applyValidator.validate(EVENT_ID, request, now)).thenReturn(new OrgRegistrationCreateContext(event, List.of()));
    }

    private OrgRegistrationModificationAccessContext givenAccess(OrgRegistrationModificationRequest request,
            Organization organization) {
        OrgRegistrationModificationAccessContext access = mock(OrgRegistrationModificationAccessContext.class);
        when(access.organization()).thenReturn(organization);
        // 이미 동의한 단체가 동의를 유지하는 요청이라 동의 처리는 바뀌지 않는다.
        when(organization.isGuardianConsent()).thenReturn(true);
        when(request.guardianConsent()).thenReturn(true);
        return access;
    }

    private OrgRegistrationModificationCandidateContext givenCandidates(OrgRegistrationModificationAccessContext access,
            Event event, OrgRegistrationModificationCandidateContext.ParticipantCandidate... members) {
        OrgRegistrationModificationCandidateContext candidate = mock(OrgRegistrationModificationCandidateContext.class);
        when(candidate.event()).thenReturn(event);
        when(candidate.registrations()).thenReturn(List.of(members));
        when(candidateValidator.validateOrganizationModificationCandidates(access)).thenReturn(candidate);
        return candidate;
    }

    /** current가 null이면 새 구성원, 아니면 기존 구성원의 수정 후보다. */
    private static OrgRegistrationModificationCandidateContext.ParticipantCandidate member(Registration current) {
        OrgRegistrationModificationCandidateContext.ParticipantCandidate member =
                mock(OrgRegistrationModificationCandidateContext.ParticipantCandidate.class);
        lenient().when(member.currentRegistration()).thenReturn(current);
        return member;
    }

    private static Event eventWithGroupRegistration(Boolean enabled) {
        Event event = mock(Event.class);
        lenient().when(event.getGroupRegistrationEnabled()).thenReturn(enabled);
        return event;
    }
}
