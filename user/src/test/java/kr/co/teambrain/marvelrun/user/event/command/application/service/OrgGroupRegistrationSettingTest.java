package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.EventFormat;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ParticipantAuthMethod;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.event.command.application.context.OrgRegistrationModificationAccessContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationModificationParticipantRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.OrgRegistrationApplyValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.OrgRegistrationModificationAccessValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.OrgRegistrationPersonalInformationValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationModificationAccessValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationPersonalInformationValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationPolicyValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.loader.RegistrationPolicyLoader;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategoryCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategorySouvenirCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** 대회의 단체 신청 설정이 단체 생성과 구성원 추가만 막는지 확인한다. 생성은 구성원 검증 전에, 수정은 잠금 전에 막는다. DB는 쓰지 않는다. */
@ExtendWith(MockitoExtension.class)
class OrgGroupRegistrationSettingTest {

    private static final String EVENT_ID = "event";
    private static final String ORGANIZATION_ID = "organization";
    /** 다음 단계에 도달했는지만 보려고 그 단계에서 던지게 하는 표시 오류다. */
    private static final ErrorCode REACHED_NEXT_STEP = ErrorCode.EVENT_NOT_OPEN;
    private final LocalDateTime now = LocalDateTime.of(2026, 11, 4, 12, 0);

    // 단체 생성 검증기
    @Mock private RegistrationCommandRepository registrations;
    @Mock private EventCommandRepository events;
    @Mock private EventCategoryCommandRepository categories;
    @Mock private EventCategorySouvenirCommandRepository souvenirs;
    @Mock private RegistrationPolicyLoader policyLoader;
    @Mock private RegistrationPolicyValidator policyValidator;
    @InjectMocks private OrgRegistrationApplyValidator applyValidator;

    // 단체 수정 트랜잭션
    @Mock private RegistrationStartGroupResolver startGroups;
    @Mock private RegistrationPersonalModificationService personalService;
    @Mock private OrgRegistrationModificationService organizationService;
    @Mock private RegistrationModificationSettlementService settlementService;
    @Mock private ServerTimeProvider time;
    @Mock private RegistrationModificationAccessValidator accessValidator;
    @Mock private RegistrationPersonalInformationValidator informationValidator;
    @Mock private RegistrationModificationClassifier classifier;
    @Mock private RegistrationPersonalInformationService informationService;
    @Mock private OrgRegistrationModificationAccessValidator organizationAccessValidator;
    @Mock private OrgRegistrationPersonalInformationValidator organizationInformationValidator;
    @Mock private OrgRegistrationPersonalInformationService organizationInformationService;
    @Mock private RegistrationActionPolicyService actionPolicies;
    @InjectMocks private RegistrationModificationTransactionService modifications;

    /** 단체 신청을 끈 대회는 대회 상태 검사 직후, 단체장·구성원 검증 전에 거절한다. */
    @Test
    void creationRejectsBeforeMemberValidationWhenDisabled() {
        OrgRegistrationCreateRequest request = mock(OrgRegistrationCreateRequest.class);
        Event event = event(false);
        when(events.findById(EVENT_ID)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> applyValidator.validate(EVENT_ID, request, now))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.GROUP_REGISTRATION_DISABLED));
        verify(policyValidator).validateNewRegistrationPeriod(event, now);
        verifyNoInteractions(request, registrations, categories, souvenirs, policyLoader);
    }

    /** 값이 비어 있으면 컬럼 기본값(허용)으로 보고 단체장 검증으로 넘어간다. */
    @Test
    void creationTreatsUnsetSettingAsEnabled() {
        OrgRegistrationCreateRequest request = mock(OrgRegistrationCreateRequest.class);
        when(events.findById(EVENT_ID)).thenReturn(Optional.of(event(null)));
        when(request.profile()).thenThrow(new CustomException(REACHED_NEXT_STEP));

        assertThatThrownBy(() -> applyValidator.validate(EVENT_ID, request, now))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(REACHED_NEXT_STEP));
    }

    /** 빌더로 만든 대회도 컬럼 기본값과 같은 설정을 갖는다. */
    @Test
    void builderKeepsColumnDefaults() {
        Event event = Event.builder().id(EVENT_ID).build();

        assertThat(event.getGroupRegistrationEnabled()).isTrue();
        assertThat(event.getParticipantAuthMethod())
                .isEqualTo(ParticipantAuthMethod.PERSONAL_INFO_PASSWORD);
        assertThat(event.getBibAutoIssue()).isFalse();
        assertThat(event.getLastBibSeq()).isZero();
        assertThat(event.getEventFormat())
                .isEqualTo(EventFormat.OFFLINE);
    }

    /** 단체 신청을 끈 대회에서 새 구성원을 더하는 수정은 그룹 확정·정책 조회·잠금 전에 거절한다. */
    @Test
    void modificationRejectsAddingMemberBeforeLocksWhenDisabled() {
        OrgRegistrationModificationRequest request = givenModification(false, "r1", null);

        assertThatThrownBy(() -> modifications.modifyOrganizationRegistration(EVENT_ID, ORGANIZATION_ID, request))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.GROUP_REGISTRATION_DISABLED));
        verifyNoInteractions(startGroups, actionPolicies, organizationService,
                organizationInformationService, settlementService);
    }

    /** 단체 신청을 꺼도 기존 구성원만 남기는 수정은 설정 검사를 지나 다음 단계(그룹 확정)로 간다. */
    @Test
    void modificationKeepsExistingMembersWhenDisabled() {
        OrgRegistrationModificationRequest request = givenModification(false, "r1", "r2");
        when(startGroups.resolveRegistrationStartGroups(anyList())).thenThrow(new CustomException(REACHED_NEXT_STEP));

        assertThatThrownBy(() -> modifications.modifyOrganizationRegistration(EVENT_ID, ORGANIZATION_ID, request))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(REACHED_NEXT_STEP));
    }

    /** 단체 신청을 켠 대회는 새 구성원이 있어도 설정 검사를 지나간다. */
    @Test
    void modificationAllowsAddingMemberWhenEnabled() {
        OrgRegistrationModificationRequest request = givenModification(true, "r1", null);
        when(startGroups.resolveRegistrationStartGroups(anyList())).thenThrow(new CustomException(REACHED_NEXT_STEP));

        assertThatThrownBy(() -> modifications.modifyOrganizationRegistration(EVENT_ID, ORGANIZATION_ID, request))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(REACHED_NEXT_STEP));
    }

    /** registrationId가 null인 항목이 새 구성원이다. */
    private OrgRegistrationModificationRequest givenModification(boolean enabled, String... registrationIds) {
        OrgRegistrationModificationRequest request = mock(OrgRegistrationModificationRequest.class);
        List<OrgRegistrationModificationParticipantRequest> members = new ArrayList<>();
        for (String registrationId : registrationIds) {
            OrgRegistrationModificationParticipantRequest member = mock(OrgRegistrationModificationParticipantRequest.class);
            lenient().when(member.registrationId()).thenReturn(registrationId);
            members.add(member);
        }
        lenient().when(request.registrations()).thenReturn(members);

        OrgRegistrationModificationAccessContext verified = mock(OrgRegistrationModificationAccessContext.class);
        when(verified.event()).thenReturn(event(enabled));
        when(time.currentDateTime()).thenReturn(now);
        when(organizationAccessValidator.validate(EVENT_ID, ORGANIZATION_ID, request, now)).thenReturn(verified);
        return request;
    }

    private static Event event(Boolean groupRegistrationEnabled) {
        return Event.builder().id(EVENT_ID).startDate(LocalDateTime.of(2026, 12, 1, 9, 0))
                .groupRegistrationEnabled(groupRegistrationEnabled).build();
    }
}
