package kr.co.teambrain.marvelrun.user.event.command.application.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationActionType;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationPolicyContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationEntry;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationItem;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.policy.EventCategoryRegistrationPolicy;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.policy.EventRegistrationPolicy;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationEntryModifyRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.RegistrationEntryListResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.RegistrationEntryModifyResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationInformationPolicyValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationPolicyValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.dto.RegistrationPolicyInput;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.loader.RegistrationPolicyLoader;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationEntryCommandRepository;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyModels.Action;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistrationEntryModificationServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 10, 0);

    @Mock private ServerTimeProvider time;
    @Mock private EntityManager entityManager;
    @Mock private RegistrationEntryCommandRepository entryRepository;
    @Mock private RegistrationInformationPolicyValidator informationPolicyValidator;
    @Mock private RegistrationActionPolicyService actionPolicies;
    @Mock private RegistrationPolicyLoader policyLoader;
    @Mock private RegistrationPolicyValidator policyValidator;
    @InjectMocks private RegistrationEntryModificationService service;

    @Mock private Registration registration;
    @Mock private Event event;
    @Mock private EventCategory avengers;
    @Mock private RegistrationEntry first;
    @Mock private RegistrationEntry second;

    @BeforeEach
    void setUp() {
        lenient().when(time.currentDateTime()).thenReturn(NOW);
        lenient().when(entityManager.find(Registration.class, "r1", LockModeType.PESSIMISTIC_WRITE)).thenReturn(registration);
        lenient().when(registration.getEvent()).thenReturn(event);
        lenient().when(registration.isProductPurchase()).thenReturn(true);
        lenient().when(event.getId()).thenReturn("event-1");
        lenient().when(first.getId()).thenReturn("e1");
        lenient().when(second.getId()).thenReturn("e2");
        lenient().when(first.getEventCategory()).thenReturn(avengers);
        lenient().when(second.getEventCategory()).thenReturn(avengers);
        lenient().when(avengers.getId()).thenReturn("avengers");
    }

    @Test
    void 요청한_받는_사람만_신청의_보호자_정보로_검증해_고친다() {
        EventRegistrationPolicy eventPolicy = mock(EventRegistrationPolicy.class);
        EventCategoryRegistrationPolicy categoryPolicy = mock(EventCategoryRegistrationPolicy.class);
        when(entryRepository.findByRegistration_Id("r1")).thenReturn(List.of(first, second));
        when(policyLoader.load("event-1", Set.of("avengers"), Set.of()))
                .thenReturn(new RegistrationPolicyContext(eventPolicy, Map.of("avengers", categoryPolicy), Map.of()));
        when(registration.getGuardianName()).thenReturn("보호자");
        when(registration.isGuardianConsent()).thenReturn(true);
        when(policyValidator.validateParticipant(event, eventPolicy,
                new RegistrationPolicyInput("2015-01-01", "보호자", true), NOW.toLocalDate()))
                .thenReturn(LocalDate.of(2015, 1, 1));

        RegistrationEntryModifyResponse response = service.modifyEntries("r1", request(entry("e1")));

        verify(informationPolicyValidator).validateStatus(registration);
        verify(informationPolicyValidator).validateRegistrationModificationPeriod(event, NOW);
        verify(actionPolicies).loadEnabledRegistrationActionPolicies("event-1", RegistrationActionType.MODIFY);
        verify(actionPolicies).validateRegistrationActionPolicy(eq(event), eq(registration), eq(Action.MODIFY),
                eq(NOW), any());
        verify(policyValidator).validateCategoryBirth(avengers, categoryPolicy, LocalDate.of(2015, 1, 1));
        verify(first).updateRecipient("새 이름", "01012345678", "2015-01-01", GenderClass.F);
        verify(second, never()).updateRecipient(any(), any(), any(), any());
        assertThat(response.entries()).hasSize(1);
    }

    @Test
    void 일반_종목_신청은_받는_사람을_고치지_않는다() {
        when(registration.isProductPurchase()).thenReturn(false);

        assertError(() -> service.modifyEntries("r1", request(entry("e1"))), ErrorCode.REGISTRATION_ENTRY_PRODUCT_ONLY);
        verifyNoInteractions(entryRepository, policyLoader);
    }

    @Test
    void 같은_참가_항목을_두_번_보내면_거절한다() {
        assertError(() -> service.modifyEntries("r1", request(entry("e1"), entry("e1"))),
                ErrorCode.REGISTRATION_ENTRY_DUPLICATED);
        verifyNoInteractions(entryRepository);
    }

    @Test
    void 다른_신청의_참가_항목은_거절하고_아무것도_고치지_않는다() {
        when(entryRepository.findByRegistration_Id("r1")).thenReturn(List.of(first));

        assertError(() -> service.modifyEntries("r1", request(entry("e1"), entry("other"))),
                ErrorCode.INVALID_REGISTRATION_ENTRIES);
        verify(first, never()).updateRecipient(any(), any(), any(), any());
    }

    @Test
    void 수정_기간이_지나면_거절하고_항목을_읽지_않는다() {
        doThrow(new CustomException(ErrorCode.REGISTRATION_PERIOD_BLOCKED))
                .when(actionPolicies).validateRegistrationActionPolicy(eq(event), eq(registration), eq(Action.MODIFY),
                        eq(NOW), any());

        assertError(() -> service.modifyEntries("r1", request(entry("e1"))),
                ErrorCode.REGISTRATION_PERIOD_BLOCKED);
        verifyNoInteractions(entryRepository);
    }

    @Test
    void 삭제된_신청과_단체_신청은_본인_신청이_아닌_것으로_본다() {
        when(registration.isSoftDeleted()).thenReturn(true);
        assertError(() -> service.modifyEntries("r1", request(entry("e1"))), ErrorCode.REGISTRATION_ACCESS_DENIED);

        when(registration.isSoftDeleted()).thenReturn(false);
        when(registration.getOrganization()).thenReturn(mock(Organization.class));
        assertError(() -> service.modifyEntries("r1", request(entry("e1"))), ErrorCode.REGISTRATION_ACCESS_DENIED);
    }

    @Test
    void 받는_사람_목록은_구매_줄_순서와_종목_순서로_돌려준다() {
        when(entityManager.find(Registration.class, "r1")).thenReturn(registration);
        RegistrationItem firstItem = mock(RegistrationItem.class);
        RegistrationItem secondItem = mock(RegistrationItem.class);
        EventCategory pack = mock(EventCategory.class);
        EventCategory ironman = mock(EventCategory.class);
        when(firstItem.getSortOrder()).thenReturn(1);
        when(secondItem.getSortOrder()).thenReturn(2);
        lenient().when(firstItem.getEventCategory()).thenReturn(avengers);
        lenient().when(secondItem.getEventCategory()).thenReturn(pack);
        lenient().when(avengers.getOrder()).thenReturn(1L);
        lenient().when(ironman.getOrder()).thenReturn(2L);
        lenient().when(ironman.getId()).thenReturn("ironman");
        RegistrationEntry packageIronman = mock(RegistrationEntry.class);
        lenient().when(packageIronman.getId()).thenReturn("e3");
        when(packageIronman.getRegistrationItem()).thenReturn(secondItem);
        when(packageIronman.getEventCategory()).thenReturn(ironman);
        when(first.getRegistrationItem()).thenReturn(firstItem);
        when(second.getRegistrationItem()).thenReturn(secondItem);
        when(entryRepository.findByRegistration_Id("r1")).thenReturn(List.of(packageIronman, second, first));

        assertThat(service.findEntries("r1").entries())
                .extracting(RegistrationEntryListResponse.Entry::entryId)
                .containsExactly("e1", "e2", "e3");
    }

    private static RegistrationEntryModifyRequest request(RegistrationEntryModifyRequest.Entry... entries) {
        return new RegistrationEntryModifyRequest(List.of(entries));
    }

    private static RegistrationEntryModifyRequest.Entry entry(String id) {
        return new RegistrationEntryModifyRequest.Entry(id, "새 이름", "01012345678", "2015-01-01", GenderClass.F);
    }

    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(CustomException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
