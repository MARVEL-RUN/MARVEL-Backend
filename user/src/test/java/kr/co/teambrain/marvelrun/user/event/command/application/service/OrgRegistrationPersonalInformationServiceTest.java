package kr.co.teambrain.marvelrun.user.event.command.application.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import kr.co.teambrain.marvelrun.common.inheritance_enum.EventStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.*;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.OrgRegistrationModificationResult;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.*;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationModificationParticipantRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.*;
import kr.co.teambrain.marvelrun.user.event.command.repository.OrganizationCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 실제 단체 분기·접근·최소 검증·보호를 연결하고 전체 수정 서비스의 호출 여부를 확인한다. */
class OrgRegistrationPersonalInformationServiceTest {
    private static final ValidatorFactory INPUTS = Validation.buildDefaultValidatorFactory();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 20, 12, 0);
    private final RegistrationCommandRepository repository = mock(RegistrationCommandRepository.class);
    private final OrganizationCommandRepository organizations = mock(OrganizationCommandRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final OrgRegistrationModificationService full = mock(OrgRegistrationModificationService.class);
    private final RegistrationPersonalModificationService personal = mock(RegistrationPersonalModificationService.class);
    private final RegistrationModificationSettlementService settlement = mock(RegistrationModificationSettlementService.class);
    private final ServerTimeProvider time = mock(ServerTimeProvider.class);
    private final OrgRegistrationModificationAccessValidator access = new OrgRegistrationModificationAccessValidator(organizations, repository);
    private final OrgRegistrationPersonalInformationValidator validator = new OrgRegistrationPersonalInformationValidator(
            INPUTS.getValidator(), new RegistrationInformationPolicyValidator(new RegistrationPolicyValidator()),
            new RegistrationUniqueInfoValidator(repository));
    private final RegistrationModificationTransactionService commands = new RegistrationModificationTransactionService(
            personal, full, settlement, time, mock(RegistrationModificationAccessValidator.class),
            mock(RegistrationPersonalInformationValidator.class), new RegistrationModificationClassifier(),
            mock(RegistrationPersonalInformationService.class), access, validator,
            new OrgRegistrationPersonalInformationService(new OrgRegistrationModificationGuard(entityManager, repository, access),
                    validator, repository));
    private Organization organization;
    private List<Registration> members;

    /** 테스트 전용 Bean Validation 자원을 닫는다. */
    @AfterAll
    static void closeValidatorFactory() { INPUTS.close(); }

    /** 최초 비교와 잠금 현재 읽기가 같은 두 구성원을 반환하도록 준비한다. */
    @BeforeEach
    void prepare() {
        Event event = Event.builder().id("e").eventStatus(EventStatus.OPEN)
                .startDate(NOW.plusDays(10)).registStartDate(NOW.minusDays(1)).registDeadline(NOW.plusDays(1)).build();
        organization = Organization.builder().id("o").event(event).loginId("group-test").password("Test1234!")
                .guardianConsent(true).email("test@example.com").address("테스트 주소").addressDetail("상세")
                .leaderName("테스트 단체장").leaderBirth("1990-01-01").leaderPhNum("010-0000-0000").build();
        members = List.of(member("a", event), member("b", event));
        when(time.currentDateTime()).thenReturn(NOW);
        when(organizations.findModificationTarget("e", "o")).thenReturn(Optional.of(organization));
        when(repository.findAllActiveByEventAndOrganization("e", "o")).thenReturn(members);
        when(repository.lockActiveOrganizationVersions("e", "o"))
                .thenReturn(List.of(new Object[]{"a", 7L}, new Object[]{"b", 7L}));
    }

    /** 변경 구성원만 정정하고 금융·정책 필드와 변경 없는 구성원의 개인정보를 유지한다. */
    @Test
    void changesOnlyInformationAndSkipsFullServices() {
        OrgRegistrationModificationParticipantRequest first = participant("a", "새 이름", "c");
        first = new OrgRegistrationModificationParticipantRequest(first.registrationId(), first.eventCategoryId(),
                first.selectedSouvenirList(), first.name(), "010-2222-3333", first.birth(), GenderClass.F);
        RegistrationModificationSettlementResult result = commands.modifyOrganization("e", "o",
                request(first, participant("b", "b", "c")));
        assertThat(members.getFirst().getName()).isEqualTo("새 이름");
        assertThat(members.getFirst().getPhNum()).isEqualTo("010-2222-3333");
        assertThat(members.getFirst().getGender()).isEqualTo(GenderClass.F);
        assertThat(members.getFirst().getBirth()).isEqualTo("1990-01-01");
        assertThat(members.getFirst().getEventCategory().getId()).isEqualTo("c");
        assertThat(members.getFirst().getContractAmount()).isEqualByComparingTo("10000");
        assertThat(members.get(1).getName()).isEqualTo("b");
        assertThat(result.members()).hasSize(2).allSatisfy(value -> {
            assertThat(value.status()).isEqualTo(RegistrationStatus.CONFIRMED);
            assertThat(value.paidAmount()).isEqualByComparingTo("5000");
            assertThat(value.balance()).isEqualByComparingTo("5000");
        });
        assertThat(result.orders()).isEmpty();
        verify(entityManager).refresh(organization, LockModeType.PESSIMISTIC_WRITE);
        verify(repository).flush();
        verifyNoInteractions(full, personal, settlement);
    }

    /** 변경 없음도 구성원 집합을 보호하지만 저장이나 금융 처리는 하지 않는다. */
    @Test
    void noChangeLocksMembersWithoutWriting() {
        assertThat(commands.modifyOrganization("e", "o", request(participant("b", "b", "c"), participant("a", "a", "c")))
                .orders()).isEmpty();
        verify(repository).lockActiveOrganizationVersions("e", "o");
        verify(repository, never()).flush();
        verifyNoInteractions(full, personal, settlement);
    }

    /** 개인정보 변경과 종목 변경이 섞이면 요청 전체를 기존 경로로 전달한다. */
    @Test
    void mixedChangesUseFullPathBeforeInformationLocks() {
        OrgRegistrationModificationRequest request = request(participant("a", "정정", "c"), participant("b", "b", "other"));
        OrgRegistrationModificationResult fullResult = new OrgRegistrationModificationResult("o", List.of());
        when(full.modify(eq("e"), eq("o"), same(request), eq(NOW), any())).thenReturn(fullResult);
        commands.modifyOrganization("e", "o", request);
        verify(full).modify(eq("e"), eq("o"), same(request), eq(NOW), any());
        verify(settlement).settle("e", "o", List.of(), NOW);
        verifyNoInteractions(entityManager);
        assertThat(members.getFirst().getName()).isEqualTo("a");
    }

    /** 구성원 추가·삭제는 단체 개인정보 서비스의 보호와 저장을 먼저 수행하지 않는다. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void membershipChangesUseFullPath(boolean add) {
        OrgRegistrationModificationRequest request = add
                ? request(participant("a", "a", "c"), participant("b", "b", "c"), participant(null, "new", "c"))
                : request(participant("a", "a", "c"));
        when(full.modify(eq("e"), eq("o"), same(request), eq(NOW), any()))
                .thenReturn(new OrgRegistrationModificationResult("o", List.of()));
        commands.modifyOrganization("e", "o", request);
        verify(full).modify(eq("e"), eq("o"), same(request), eq(NOW), any());
        verifyNoInteractions(entityManager);
    }

    /** 최종목록 중복과 외부 중복은 어느 구성원도 변경하기 전에 거절한다. */
    @Test
    void duplicateFinalOrExternalIdentityDoesNotPartiallyModify() {
        expect(ErrorCode.REGISTRATION_ALREADY_EXISTS, request(participant("a", "same", "c"), participant("b", "same", "c")));
        when(repository.existsOtherActiveByEventIdAndUniqueInfo("e", "b", "external", "010-1111-2222", "1990-01-01"))
                .thenReturn(true);
        expect(ErrorCode.REGISTRATION_ALREADY_EXISTS, request(participant("a", "정정", "c"), participant("b", "external", "c")));
        assertThat(members).extracting(Registration::getName).containsExactly("a", "b");
    }

    /** 인증과 필수 입력 실패는 잠금 전에 차단한다. */
    @Test
    void invalidInputAndAccessFailBeforeLocks() {
        expect(ErrorCode.INVALID_REGISTRATION_MODIFICATION_ARGUMENT, request(participant("a", " ", "c"), participant("b", "b", "c")));
        expect(ErrorCode.ORGANIZATION_ACCESS_DENIED, new OrgRegistrationModificationRequest(true,
                "test@example.com",
                "테스트 주소",
                "상세",
                "테스트 단체장",
                java.time.LocalDate.of(1990, 1, 1),
                "010-0000-0000",
                new OrganizationAccessRequest("wrong", "Test1234!"),
                List.of(participant("a", "a", "c"), participant("b", "b", "c"))));
        verifyNoInteractions(entityManager);
    }

    /** 판정 이후 구성원이 추가되거나 version이 바뀐 경우 모두 오래된 요청으로 거절한다. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void staleMembershipOrVersionIsRejected(boolean added) {
        when(repository.lockActiveOrganizationVersions("e", "o")).thenReturn(added
                ? List.of(new Object[]{"a", 7L}, new Object[]{"b", 7L}, new Object[]{"new", 0L})
                : List.of(new Object[]{"a", 8L}, new Object[]{"b", 7L}));
        expect(ErrorCode.CONCURRENT_MODIFICATION, request(participant("a", "정정", "c"), participant("b", "b", "c")));
        assertThat(members.getFirst().getName()).isEqualTo("a");
    }

    /** 뒤쪽 구성원이 수정 불가 상태라도 앞 구성원의 정정은 반영하지 않는다. */
    @Test
    void validatesEveryMemberBeforeWriting() {
        Registration blocked = member("b", organization.getEvent());
        org.springframework.test.util.ReflectionTestUtils.setField(blocked, "status", RegistrationStatus.CANCELED);
        when(repository.findAllActiveByEventAndOrganization("e", "o")).thenReturn(List.of(members.getFirst(), blocked));
        expect(ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET, request(participant("a", "정정", "c"), participant("b", "b", "c")));
        assertThat(members.getFirst().getName()).isEqualTo("a");
    }

    /** 저장된 단체 참가자를 외부 조회 없이 구성한다. */
    private Registration member(String id, Event event) {
        return Registration.builder().id(id).event(event).organization(organization)
                .eventCategory(EventCategory.builder().id("c").build()).souvenirJson(List.of(new SouvenirJson("s", "S")))
                .name(id).phNum("010-1111-2222").birth("1990-01-01").gender(GenderClass.M).version(7L)
                .status(RegistrationStatus.CONFIRMED).contractAmount(new BigDecimal("10000")).paidAmount(new BigDecimal("5000")).build();
    }

    /** fixture 정책값과 원하는 개인정보를 합쳐 기존 DTO를 만든다. */
    private OrgRegistrationModificationParticipantRequest participant(String id, String name, String category) {
        return new OrgRegistrationModificationParticipantRequest(id, category, List.of(new SouvenirJson("s", "S")),
                name, "010-1111-2222", "1990-01-01", GenderClass.M);
    }

    /** 최종 전체목록과 테스트 인증정보를 담는다. */
    private OrgRegistrationModificationRequest request(OrgRegistrationModificationParticipantRequest... participants) {
        return new OrgRegistrationModificationRequest(true,
                "test@example.com",
                "테스트 주소",
                "상세",
                "테스트 단체장",
                java.time.LocalDate.of(1990, 1, 1),
                "010-0000-0000",
                new OrganizationAccessRequest("group-test", "Test1234!"),
                List.of(participants));
    }

    /** 업무 오류와 부분 저장·전체 경로 미호출을 함께 확인한다. */
    private void expect(ErrorCode code, OrgRegistrationModificationRequest request) {
        assertThatThrownBy(() -> commands.modifyOrganization("e", "o", request)).isInstanceOfSatisfying(CustomException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
        verify(repository, never()).flush();
        verifyNoInteractions(full, personal, settlement);
    }
}
