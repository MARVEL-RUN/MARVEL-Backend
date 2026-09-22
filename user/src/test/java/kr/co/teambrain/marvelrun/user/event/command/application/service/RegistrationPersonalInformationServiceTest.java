package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.user.event.command.repository.EventRegistrationPolicyRepository;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.policy.EventRegistrationPolicy;
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
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationPersonalModificationResult;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationModificationAccessValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationPersonalInformationValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationPolicyValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationUniqueInfoValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationInformationPolicyValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.OrgRegistrationModificationAccessValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.OrgRegistrationPersonalInformationValidator;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 실제 CommandService 분기와 개인정보 검증을 연결하여 불필요한 서비스 호출 생략을 확인한다. */
class RegistrationPersonalInformationServiceTest {
    private static final ValidatorFactory INPUTS = Validation.buildDefaultValidatorFactory();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 20, 12, 0);
    private final RegistrationCommandRepository repository = mock(RegistrationCommandRepository.class);
    private final EventRegistrationPolicyRepository guardianPolicies = mock(EventRegistrationPolicyRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final RegistrationPersonalModificationService full = mock(RegistrationPersonalModificationService.class);
    private final OrgRegistrationModificationService organization = mock(OrgRegistrationModificationService.class);
    private final RegistrationModificationSettlementService settlement = mock(RegistrationModificationSettlementService.class);
    private final ServerTimeProvider time = mock(ServerTimeProvider.class);
    private final RegistrationPersonalInformationValidator validator = new RegistrationPersonalInformationValidator(
            INPUTS.getValidator(), new RegistrationInformationPolicyValidator(new RegistrationPolicyValidator()), new RegistrationUniqueInfoValidator(repository),
            guardianPolicies, new RegistrationPolicyValidator());
    private final RegistrationModificationTransactionService commands = new RegistrationModificationTransactionService(
            full, organization, settlement, time, new RegistrationModificationAccessValidator(repository), validator,
            new RegistrationModificationClassifier(), new RegistrationPersonalInformationService(validator, repository, entityManager),
            mock(OrgRegistrationModificationAccessValidator.class), mock(OrgRegistrationPersonalInformationValidator.class),
            mock(OrgRegistrationPersonalInformationService.class));

    /** 테스트에서 생성한 Bean Validation 자원만 닫는다. */
    @AfterAll
    static void closeValidatorFactory() { INPUTS.close(); }

    /** 네 허용 상태의 개인정보만 바뀌고 정산 없이 기존 금융 요약과 빈 신규 주문 목록을 반환한다. */
    @ParameterizedTest
    @EnumSource(value = RegistrationStatus.class, names = {"PAYMENT_PENDING", "CONFIRMED", "ADDITIONAL_PAYMENT_REQUIRED", "PARTIAL_REFUND_REQUIRED"})
    void updatesOnlyPersonalInformation(RegistrationStatus status) {
        Registration current = fixture(status, NOW.minusDays(1), NOW.plusDays(1), EventStatus.OPEN);
        RegistrationModificationSettlementResult result = commands.modifyPersonal("e", "r", request("새 이름", "c", "1990-01-01"));
        assertThat(current.getName()).isEqualTo("새 이름");
        assertThat(current.getAddress()).isEqualTo("정정 주소");
        assertThat(current.getBirth()).isEqualTo("1990-01-01");
        assertThat(current.getContractAmount()).isEqualByComparingTo("10000");
        assertThat(current.getPaidAmount()).isEqualByComparingTo("5000");
        assertThat(current.getStatus()).isEqualTo(status);
        assertThat(result.orders()).isEmpty();
        assertThat(result.members().getFirst().balance()).isEqualByComparingTo("5000");
        verify(repository).flush();
        verifyNoInteractions(full, organization, settlement, entityManager, guardianPolicies);
    }

    /** 변경 없음은 flush나 정산 없이 낙관적 읽기 보호만 등록한다. */
    @Test
    void noChangeDoesNotWrite() {
        fixture(RegistrationStatus.CONFIRMED, NOW.minusDays(1), NOW.plusDays(1), EventStatus.OPEN);
        RegistrationModificationSettlementResult result = commands.modifyPersonal("e", "r", request("기존 이름", "c", "1990-01-01"));
        assertThat(result.orders()).isEmpty();
        verify(repository, never()).flush();
        verify(entityManager).lock(any(Registration.class), eq(LockModeType.OPTIMISTIC));
        verifyNoInteractions(full, organization, settlement);
    }

    /** 전체 수정은 최초 비교 version을 전달하고 기존 후속 정산으로 연결한다. */
    @ParameterizedTest
    @ValueSource(strings = {"category", "birth"})
    void policyChangeUsesFullPath(String field) {
        fixture(RegistrationStatus.CONFIRMED, NOW.minusDays(1), NOW.plusDays(1), EventStatus.OPEN);
        RegistrationModificationRequest request = request("기존 이름", field.equals("category") ? "other" : "c",
                field.equals("birth") ? "1990-01-02" : "1990-01-01");
        RegistrationPersonalModificationResult modified = mock(RegistrationPersonalModificationResult.class);
        when(modified.registrationId()).thenReturn("r");
        when(full.modify("e", "r", request, NOW, 7L)).thenReturn(modified);
        commands.modifyPersonal("e", "r", request);
        verify(settlement).settle("e", null, List.of("r"), NOW);
        verify(repository, never()).flush();
        verifyNoInteractions(entityManager);
    }

    /** 별도 관리자 대기·취소·만료 상태는 개인정보 정정으로 확대하지 않는다. */
    @ParameterizedTest
    @EnumSource(value = RegistrationStatus.class, names = {"PENDING", "CANCELLATION_PENDING", "CANCELED", "EXPIRED"})
    void rejectsDisallowedStates(RegistrationStatus status) {
        fixture(status, NOW.minusDays(1), NOW.plusDays(1), EventStatus.OPEN);
        expect(ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET, request("새 이름", "c", "1990-01-01"));
    }

    /** 기존 기간 경계와 OPEN 제한을 추가 정책 조회 없이 유지한다. */
    @Test
    void periodAndEventRestrictions() {
        fixture(RegistrationStatus.CONFIRMED, NOW.plusSeconds(1), NOW.plusDays(1), EventStatus.OPEN);
        expect(ErrorCode.EVENT_REGISTRATION_NOT_STARTED, request("새 이름", "c", "1990-01-01"));
        fixture(RegistrationStatus.CONFIRMED, NOW.minusDays(1), NOW, EventStatus.OPEN);
        expect(ErrorCode.EVENT_REGISTRATION_CLOSED, request("새 이름", "c", "1990-01-01"));
        fixture(RegistrationStatus.CONFIRMED, NOW.minusDays(1), NOW.plusDays(1), EventStatus.CLOSED);
        expect(ErrorCode.EVENT_NOT_OPEN, request("새 이름", "c", "1990-01-01"));
    }

    /** 시작 정각은 기존 정책과 동일하게 허용한다. */
    @Test
    void acceptsStartBoundary() {
        fixture(RegistrationStatus.CONFIRMED, NOW, NOW.plusDays(1), EventStatus.OPEN);
        assertThat(commands.modifyPersonal("e", "r", request("새 이름", "c", "1990-01-01"))).isNotNull();
    }

    /** 입력 누락·인증 실패·활성 중복은 엔티티를 변경하거나 전체 경로를 호출하기 전에 차단한다. */
    @Test
    void invalidInputAccessAndDuplicateLeaveEntityUntouched() {
        Registration current = fixture(RegistrationStatus.CONFIRMED, NOW.minusDays(1), NOW.plusDays(1), EventStatus.OPEN);
        expect(ErrorCode.INVALID_REGISTRATION_MODIFICATION_ARGUMENT, request(" ", "c", "1990-01-01"));
        RegistrationModificationRequest original = request("새 이름", "c", "1990-01-01");
        RegistrationModificationRequest denied = new RegistrationModificationRequest(new RegistrationAccessRequest("다른 인증", "1990-01-01", "010-1111-2222", "test-only"),
                original.eventCategoryId(),
                original.selectedSouvenirList(),
                original.name(),
                original.phNum(),
                original.birth(),
                original.gender(),
                original.address(),
                original.addressDetail(),
                original.guardianConsent(),
                original.guardianName(),
                original.guardianPhNum(),
                original.guardianRelationship(),
                original.email());
        expect(ErrorCode.REGISTRATION_ACCESS_DENIED, denied);
        when(repository.existsOtherActiveByEventIdAndUniqueInfo("e", "r", "새 이름", "010-1111-2222", "1990-01-01"))
                .thenReturn(true);
        expect(ErrorCode.REGISTRATION_ALREADY_EXISTS, original);
        assertThat(current.getName()).isEqualTo("기존 이름");
    }

    /** 보호자 이름 변경은 실제 정책 검증을 거치며 거절 시 금융 요약과 개인정보를 보존한다. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void guardianNameChangeValidatesPolicyBeforeWriting(boolean consent) {
        Registration current = fixture(RegistrationStatus.CONFIRMED, NOW.minusDays(1), NOW.plusDays(1), EventStatus.OPEN);
        EventRegistrationPolicy policy = mock(EventRegistrationPolicy.class);
        when(policy.getEvent()).thenReturn(current.getEvent());
        when(policy.getGuardianRequiredBirthFrom()).thenReturn(java.time.LocalDate.of(1990, 1, 1));
        when(guardianPolicies.findByEventId("e")).thenReturn(Optional.of(policy));
        RegistrationModificationRequest original = request("기존 이름", "c", "1990-01-01");
        RegistrationModificationRequest changed = new RegistrationModificationRequest(original.access(),
                original.eventCategoryId(),
                original.selectedSouvenirList(),
                original.name(),
                original.phNum(),
                original.birth(),
                original.gender(),
                original.address(),
                original.addressDetail(),
                consent,
                "보호자",
                "010-3333-4444",
                "부",
                original.email());
        if (consent) {
            assertThat(commands.modifyPersonal("e", "r", changed).orders()).isEmpty();
            assertThat(current.getGuardianName()).isEqualTo("보호자");
            assertThat(current.getGuardianPhNum()).isEqualTo("010-3333-4444");
            assertThat(current.getGuardianRelationship()).isEqualTo("부");
            assertThat(current.isGuardianConsent()).isTrue();
            verify(repository).flush();
        } else {
            expect(ErrorCode.GUARDIAN_CONSENT_REQUIRED, changed);
            assertThat(current.getGuardianName()).isNull();
            assertThat(current.getGuardianPhNum()).isNull();
            assertThat(current.getGuardianRelationship()).isNull();
            assertThat(current.isGuardianConsent()).isFalse();
        }
        verify(guardianPolicies, atLeastOnce()).findByEventId("e");
        assertThat(current.getName()).isEqualTo("기존 이름");
        assertThat(current.getContractAmount()).isEqualByComparingTo("10000");
        assertThat(current.getPaidAmount()).isEqualByComparingTo("5000");
        assertThat(current.getStatus()).isEqualTo(RegistrationStatus.CONFIRMED);
        verifyNoInteractions(full, organization, settlement, entityManager);
    }

    /** DB 없이 접근·분류에 필요한 저장 snapshot만 구성한다. */
    private Registration fixture(RegistrationStatus status, LocalDateTime start, LocalDateTime deadline, EventStatus eventStatus) {
        Event event = Event.builder().id("e").eventStatus(eventStatus).registStartDate(start).registDeadline(deadline).build();
        Registration current = Registration.builder().id("r").event(event).eventCategory(EventCategory.builder().id("c").build())
                .souvenirJson(List.of(new SouvenirJson("s", "M"))).name("기존 이름").phNum("010-1111-2222")
                .password("test-only").birth("1990-01-01").gender(GenderClass.M).address("정정 주소").addressDetail("상세")
                .guardianName(null).guardianConsent(false).status(status).contractAmount(new BigDecimal("10000"))
                .paidAmount(new BigDecimal("5000")).version(7L).build();
        when(time.currentDateTime()).thenReturn(NOW);
        when(repository.findActivePersonalModificationTarget("e", "r")).thenReturn(Optional.of(current));
        return current;
    }

    /** 변경할 업무값과 현재 인증값을 분리하여 요청을 만든다. */
    private RegistrationModificationRequest request(String name, String category, String birth) {
        return new RegistrationModificationRequest(new RegistrationAccessRequest("기존 이름", "1990-01-01", "010-1111-2222", "test-only"),
                category,
                List.of(new SouvenirJson("s", "M")),
                name,
                "010-1111-2222",
                birth,
                GenderClass.M,
                "정정 주소",
                "상세",
                false,
                null,
                null,
                null,
                null);
    }

    /** 업무 오류와 저장·전체 경로 미호출을 함께 확인한다. */
    private void expect(ErrorCode code, RegistrationModificationRequest request) {
        assertThatThrownBy(() -> commands.modifyPersonal("e", "r", request)).isInstanceOfSatisfying(CustomException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
        verify(repository, never()).flush();
        verifyNoInteractions(full, organization, settlement);
    }
}
