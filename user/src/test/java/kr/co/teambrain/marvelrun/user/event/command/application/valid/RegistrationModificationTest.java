package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import kr.co.teambrain.marvelrun.common.inheritance_enum.EventStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.context.OrgRegistrationModificationAccessContext;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationModificationAccessContext;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationPolicyContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.*;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.policy.*;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.*;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.*;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.loader.RegistrationPolicyLoader;
import kr.co.teambrain.marvelrun.user.event.command.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * 실제 생성·수정 Validator를 통해 공통 정책 연결과 후보 불변 경계를 확인한다.
 *
 * Repository와 PolicyLoader만 mock 처리하며,
 * 참가 정책 판단은 실제 RegistrationPolicyValidator가 수행한다.
 */
class RegistrationModificationTest {

    private static final String EVENT_ID = "event";
    private static final String CATEGORY_ID = "category";
    private static final String PHONE = "010-1111-2222";

    private static final LocalDateTime NOW =
            LocalDateTime.of(2026, 9, 22, 12, 0);

    private static final LocalDate EVENT_DATE =
            LocalDate.of(2026, 11, 1);

    private RegistrationCommandRepository registrations;
    private EventCommandRepository events;
    private EventCategoryCommandRepository categories;
    private EventCategorySouvenirCommandRepository mappings;
    private RegistrationPolicyLoader loader;

    private Event event;
    private EventCategory category;
    private EventCategoryRegistrationPolicy categoryPolicy;

    private RegistrationApplyValidator personalCreate;
    private OrgRegistrationApplyValidator groupCreate;
    private RegistrationModificationCandidateValidator personalModify;
    private OrgRegistrationModificationCandidateValidator groupModify;

    /**
     * 같은 종목·정책 fixture를 생성과 수정에 함께 연결한다.
     */
    @BeforeEach
    void setUp() {
        registrations = mock(RegistrationCommandRepository.class);
        events = mock(EventCommandRepository.class);
        categories = mock(EventCategoryCommandRepository.class);
        mappings = mock(EventCategorySouvenirCommandRepository.class);
        loader = mock(RegistrationPolicyLoader.class);

        event = mock(Event.class);
        category = mock(EventCategory.class);
        categoryPolicy = mock(EventCategoryRegistrationPolicy.class);

        when(event.getId()).thenReturn(EVENT_ID);
        when(event.getEventStatus()).thenReturn(EventStatus.OPEN);
        when(event.getStartDate()).thenReturn(EVENT_DATE.atStartOfDay());
        when(event.getRegistStartDate()).thenReturn(NOW.minusDays(1));
        when(event.getRegistDeadline()).thenReturn(NOW.plusDays(1));

        when(events.findById(EVENT_ID)).thenReturn(Optional.of(event));

        when(category.getId()).thenReturn(CATEGORY_ID);
        when(category.getEvent()).thenReturn(event);
        when(category.getIsActive()).thenReturn(true);

        when(categories.findById(CATEGORY_ID))
                .thenReturn(Optional.of(category));

        Souvenir shirt = mock(Souvenir.class);
        when(shirt.getId()).thenReturn("shirt");
        when(shirt.getEvent()).thenReturn(event);
        when(shirt.getIsActive()).thenReturn(true);
        when(shirt.getSizes()).thenReturn("130|150|M");

        EventCategorySouvenir mapping =
                mock(EventCategorySouvenir.class);

        when(mapping.getId()).thenReturn("mapping");
        when(mapping.getEventCategory()).thenReturn(category);
        when(mapping.getSouvenir()).thenReturn(shirt);

        when(mappings.findAllMappingsByCategoryId(CATEGORY_ID))
                .thenReturn(List.of(mapping));

        EventRegistrationPolicy eventPolicy =
                mock(EventRegistrationPolicy.class);

        when(eventPolicy.getEvent()).thenReturn(event);
        when(eventPolicy.getGuardianRequiredBirthFrom())
                .thenReturn(LocalDate.of(2012, 11, 2));

        when(categoryPolicy.getEventCategory()).thenReturn(category);

        EventCategorySouvenirPolicy sizePolicy =
                mock(EventCategorySouvenirPolicy.class);

        when(sizePolicy.getEventCategorySouvenir()).thenReturn(mapping);
        when(sizePolicy.getBirthFrom())
                .thenReturn(LocalDate.of(2013, 11, 2));
        when(sizePolicy.getAllowedSizes()).thenReturn("130|150");

        when(loader.load(
                EVENT_ID,
                Set.of(CATEGORY_ID),
                Set.of("mapping")
        )).thenReturn(
                new RegistrationPolicyContext(
                        eventPolicy,
                        Map.of(CATEGORY_ID, categoryPolicy),
                        Map.of("mapping", List.of(sizePolicy))
                )
        );

        RegistrationPolicyValidator policyValidator =
                new RegistrationPolicyValidator();

        personalCreate = new RegistrationApplyValidator(
                registrations, events, categories, mappings,
                loader, policyValidator
        );

        groupCreate = new OrgRegistrationApplyValidator(
                registrations, events, categories, mappings,
                loader, policyValidator
        );

        personalModify = new RegistrationModificationCandidateValidator(
                registrations, events, categories, mappings,
                loader, policyValidator, new RegistrationUniqueInfoValidator(registrations)
        );

        groupModify = new OrgRegistrationModificationCandidateValidator(
                registrations, events, categories, mappings,
                loader, policyValidator
        );
    }

    /**
     * 생성과 수정이 같은 후보를 동일하게 검증하고 기념품을 정규화한다.
     * 수정 결과를 생성해도 기존 Entity의 값은 바뀌지 않는다.
     */
    @Test
    void personalCreationAndModificationUseSamePolicy() {
        Registration current = existing("r1", null);
        var request = personalRequest("2020-01-01", " 130 ", "보호자", true);
        var before = snapshot(current);

        var created = personalCreate.validate(
                EVENT_ID,
                creationRequest(request),
                NOW
        );

        var modified = personalModify.validate(
                personalContext(current, request)
        );

        assertThat(modified.eventCategory())
                .isSameAs(created.eventCategory());
        assertThat(modified.souvenirJsons())
                .isEqualTo(created.souvenirJsons())
                .containsExactly(new SouvenirJson("shirt", "130"));
        assertThat(modified.request()).isSameAs(request);
        assertThat(snapshot(current)).isEqualTo(before);

        verify(registrations).existsOtherActiveByEventIdAndUniqueInfo(
                EVENT_ID, "r1",
                request.name(), request.phNum(), request.birth()
        );
    }

    /**
     * 변경 후 생년월일·기념품·보호자 값으로 정책을 판단한다.
     * 실패 시 현재 Entity의 값은 그대로 남는다.
     */
    @ParameterizedTest
    @CsvSource({
            "2020-01-01,M,보호자,true,REGISTRATION_SOUVENIR_SIZE_NOT_ALLOWED",
            "2020-01-01,130,,true,GUARDIAN_NAME_REQUIRED",
            "2020-01-01,130,보호자,false,GUARDIAN_CONSENT_REQUIRED",
            "2026-09-23,130,보호자,true,INVALID_REGISTRATION_BIRTH",
            "2020-02-30,130,보호자,true,INVALID_REGISTRATION_BIRTH"
    })
    void rejectsInvalidPersonalCandidate(
            String birth,
            String size,
            String guardian,
            boolean consent,
            ErrorCode expected
    ) {
        Registration current = existing("r1", null);
        var before = snapshot(current);
        var request = personalRequest(birth, size, guardian, consent);

        expectError(
                expected,
                () -> personalModify.validate(
                        personalContext(current, request)
                )
        );

        assertThat(snapshot(current)).isEqualTo(before);
    }

    /**
     * 어린이가 허용되지 않는 종목은 생성·수정 양쪽에서 동일하게 거부한다.
     */
    @Test
    void rejectsRestrictedCategoryInBothFlows() {
        when(categoryPolicy.getAllowedBirthTo())
                .thenReturn(LocalDate.of(2013, 11, 1));

        var request = personalRequest(
                "2020-01-01", "130", "보호자", true
        );

        expectError(
                ErrorCode.REGISTRATION_CATEGORY_BIRTH_NOT_ALLOWED,
                () -> personalCreate.validate(
                        EVENT_ID, creationRequest(request), NOW
                )
        );

        expectError(
                ErrorCode.REGISTRATION_CATEGORY_BIRTH_NOT_ALLOWED,
                () -> personalModify.validate(
                        personalContext(existing("r1", null), request)
                )
        );
    }

    /**
     * 다른 활성 참가자와 충돌하면 종목·정책 조회 전에 거부한다.
     */
    @Test
    void personalDuplicateStopsBeforePolicyLookup() {
        var request = personalRequest(
                "1990-01-01", "M", null, false
        );

        when(registrations.existsOtherActiveByEventIdAndUniqueInfo(
                EVENT_ID, "r1",
                request.name(), request.phNum(), request.birth()
        )).thenReturn(true);

        expectError(
                ErrorCode.REGISTRATION_ALREADY_EXISTS,
                () -> personalModify.validate(
                        personalContext(existing("r1", null), request)
                )
        );

        verifyNoInteractions(categories, mappings, loader);
    }

    /**
     * 신청 마감 정각부터 수정 후보 검증을 차단한다.
     */
    @Test
    void modificationRespectsApplicationDeadline() {
        when(event.getRegistDeadline()).thenReturn(NOW);

        expectError(
                ErrorCode.EVENT_REGISTRATION_CLOSED,
                () -> personalModify.validate(
                        personalContext(
                                existing("r1", null),
                                personalRequest("1990-01-01", "M", null, false)
                        )
                )
        );

        verifyNoInteractions(registrations, categories, mappings, loader);
    }

    /**
     * 기존·신규 혼합 단체를 요청 순서대로 반환하고,
     * 같은 종목·매핑·정책은 한 번만 조회한다.
     */
    @Test
    void mixedGroupKeepsOrderAndUsesSharedLookup() {
        Organization organization = organization("1990-01-01", true);
        Registration current = existing("r1", organization);
        var before = snapshot(current);

        var existingRequest = participant(
                "r1", "기존수정", "1990-01-01", "M"
        );
        var newRequest = participant(
                null, "신규어린이", "2020-01-01", " 150 "
        );

        var result = groupModify.validate(
                groupContext(
                        organization,
                        List.of(current),
                        List.of(existingRequest, newRequest)
                )
        );

        assertThat(result.registrations()).hasSize(2);
        assertThat(result.registrations().get(0).currentRegistration())
                .isSameAs(current);
        assertThat(result.registrations().get(0).request())
                .isSameAs(existingRequest);
        assertThat(result.registrations().get(1).currentRegistration())
                .isNull();
        assertThat(result.registrations().get(1).request())
                .isSameAs(newRequest);
        assertThat(result.registrations().get(1).souvenirJsons())
                .containsExactly(new SouvenirJson("shirt", "150"));
        assertThat(snapshot(current)).isEqualTo(before);

        verify(registrations).existsOtherActiveByEventIdAndUniqueInfo(
                EVENT_ID, "r1", "기존수정", PHONE, "1990-01-01"
        );
        verify(registrations).existsByEventIdAndUniqueInfo(
                EVENT_ID, "신규어린이", PHONE, "2020-01-01"
        );

        verify(categories, times(1)).findById(CATEGORY_ID);
        verify(mappings, times(1))
                .findAllMappingsByCategoryId(CATEGORY_ID);
        verify(loader, times(1)).load(
                EVENT_ID, Set.of(CATEGORY_ID), Set.of("mapping")
        );
    }

    /**
     * 최종 목록 내부의 참가자 정보 중복은 DB 조회 전에 거부한다.
     */
    @Test
    void rejectsDuplicateParticipantsInsideFinalGroupList() {
        var first = participant("r1", "동일인", "1990-01-01", "M");
        var second = participant(null, "동일인", "1990-01-01", "M");

        expectError(
                ErrorCode.REGISTRATION_ALREADY_EXISTS,
                () -> groupModify.validate(
                        groupContext(
                                organization("1990-01-01", true),
                                List.of(),
                                List.of(first, second)
                        )
                )
        );

        verifyNoInteractions(registrations, categories, mappings, loader);
    }

    /**
     * 신규 참가자는 기존 신규신청 DB 중복검사를 사용한다.
     */
    @Test
    void rejectsExistingDatabaseParticipantForNewMember() {
        when(registrations.existsByEventIdAndUniqueInfo(
                EVENT_ID, "신규", PHONE, "1990-01-01"
        )).thenReturn(true);

        expectError(
                ErrorCode.REGISTRATION_ALREADY_EXISTS,
                () -> groupModify.validate(
                        groupContext(
                                organization("1990-01-01", true),
                                List.of(),
                                List.of(participant(
                                        null, "신규", "1990-01-01", "M"
                                ))
                        )
                )
        );
    }

    /**
     * 기존 구성원도 자기 자신 외의 활성 신청과 충돌하면 거부한다.
     */
    @Test
    void rejectsDatabaseDuplicateForExistingMember() {
        Organization organization = organization("1990-01-01", true);
        Registration current = existing("r1", organization);

        when(registrations.existsOtherActiveByEventIdAndUniqueInfo(
                EVENT_ID, "r1", "변경", PHONE, "1990-01-01"
        )).thenReturn(true);

        expectError(
                ErrorCode.REGISTRATION_ALREADY_EXISTS,
                () -> groupModify.validate(
                        groupContext(
                                organization,
                                List.of(current),
                                List.of(participant(
                                        "r1", "변경", "1990-01-01", "M"
                                ))
                        )
                )
        );
    }

    /**
     * 현재 구성원 목록에 없는 ID는 수정 후보로 인정하지 않는다.
     */
    @Test
    void rejectsUnknownExistingMemberId() {
        expectError(
                ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET,
                () -> groupModify.validate(
                        groupContext(
                                organization("1990-01-01", true),
                                List.of(),
                                List.of(participant(
                                        "other", "타단체", "1990-01-01", "M"
                                ))
                        )
                )
        );
    }

    /**
     * 최종 목록에서 생략한 기존 구성원은 Context에 보존하고 삭제하지 않는다.
     */
    @Test
    void omittedMemberRemainsOnlyInCurrentList() {
        Organization organization = organization("1990-01-01", true);
        Registration first = existing("r1", organization);
        Registration omitted = existing("r2", organization);
        var before = snapshot(omitted);

        var result = groupModify.validate(
                groupContext(
                        organization,
                        List.of(first, omitted),
                        List.of(participant(
                                "r1", "유지", "1990-01-01", "M"
                        ))
                )
        );

        assertThat(result.currentRegistrations())
                .containsExactly(first, omitted);
        assertThat(result.registrations()).hasSize(1);
        assertThat(snapshot(omitted)).isEqualTo(before);
    }

    /**
     * 단체 어린이의 보호자 동의는 현재 Organization에서 가져온다.
     */
    @Test
    void groupUsesStoredGuardianConsent() {
        expectError(
                ErrorCode.GUARDIAN_CONSENT_REQUIRED,
                () -> groupModify.validate(
                        groupContext(
                                organization("1990-01-01", false),
                                List.of(),
                                List.of(participant(
                                        null, "어린이", "2020-01-01", "130"
                                ))
                        )
                )
        );
    }

    /**
     * 뒤 참가자의 정책검증이 실패해도 앞 참가자의 Entity를 변경하지 않는다.
     */
    @Test
    void laterGroupFailurePreservesEarlierMember() {
        Organization organization = organization("1990-01-01", true);
        Registration current = existing("r1", organization);
        var before = snapshot(current);

        expectError(
                ErrorCode.REGISTRATION_SOUVENIR_SIZE_NOT_ALLOWED,
                () -> groupModify.validate(
                        groupContext(
                                organization,
                                List.of(current),
                                List.of(
                                        participant(
                                                "r1", "변경후", "1990-01-01", "M"
                                        ),
                                        participant(
                                                null, "어린이", "2020-01-01", "M"
                                        )
                                )
                        )
                )
        );

        assertThat(snapshot(current)).isEqualTo(before);
    }

    /**
     * 저장된 단체장 문자열 생년월일을 파싱하고 19번째 생일 경계를 적용한다.
     */
    @Test
    void storedLeaderBirthUsesEventDateBoundary() {
        var requests = List.of(
                participant(null, "성인", "1990-01-01", "M")
        );

        assertThatCode(
                () -> groupModify.validate(
                        groupContext(
                                organization("2007-11-01", true),
                                List.of(),
                                requests
                        )
                )
        ).doesNotThrowAnyException();

        expectError(
                ErrorCode.ORGANIZATION_LEADER_MUST_BE_ADULT,
                () -> groupModify.validate(
                        groupContext(
                                organization("2007-11-02", true),
                                List.of(),
                                requests
                        )
                )
        );
    }

    /**
     * 저장된 단체장 생년월일 누락과 잘못된 날짜를 명시적인 오류로 반환한다.
     */
    @ParameterizedTest
    @CsvSource({
            ",ORGANIZATION_LEADER_BIRTH_REQUIRED",
            "1990-02-30,INVALID_REGISTRATION_BIRTH",
            "2026-09-23,INVALID_REGISTRATION_BIRTH"
    })
    void rejectsInvalidStoredLeaderBirth(
            String birth,
            ErrorCode expected
    ) {
        expectError(
                expected,
                () -> groupModify.validate(
                        groupContext(
                                organization(birth, true),
                                List.of(),
                                List.of(participant(
                                        null, "성인", "1990-01-01", "M"
                                ))
                        )
                )
        );
    }

    /**
     * 기존 단체 생성처럼 앞 참가자의 정책 오류를
     * 뒤 참가자의 DB 중복검사보다 먼저 반환한다.
     */
    @Test
    void groupCreationPreservesParticipantValidationOrder() {
        when(categoryPolicy.getAllowedBirthTo())
                .thenReturn(LocalDate.of(2013, 11, 1));

        when(registrations.existsByEventIdAndUniqueInfo(
                EVENT_ID, "뒤참가자", PHONE, "1990-01-01"
        )).thenReturn(true);

        OrgRegistrationCreateRequest request =
                new OrgRegistrationCreateRequest(
                        new OrgAccountRequest("단체", "group-login", "password"),
                        new OrgProfileRequest(
                                "주소", "상세",
                                LocalDate.of(1990, 1, 1),
                                PHONE, "test@example.com", "단체장", true
                        ),
                        List.of(
                                new OrgRegistrationParticipantRequest(
                                        CATEGORY_ID,
                                        souvenirs("130"),
                                        "앞어린이", PHONE,
                                        "2020-01-01", GenderClass.M
                                ),
                                new OrgRegistrationParticipantRequest(
                                        CATEGORY_ID,
                                        souvenirs("M"),
                                        "뒤참가자", PHONE,
                                        "1990-01-01", GenderClass.M
                                )
                        )
                );

        expectError(
                ErrorCode.REGISTRATION_CATEGORY_BIRTH_NOT_ALLOWED,
                () -> groupCreate.validate(EVENT_ID, request, NOW)
        );

        verify(registrations, never()).existsByEventIdAndUniqueInfo(
                EVENT_ID, "뒤참가자", PHONE, "1990-01-01"
        );
    }

    /**
     * 테스트용 정규화 전 기념품 목록을 구성한다.
     */
    private List<SouvenirJson> souvenirs(String size) {
        return List.of(new SouvenirJson("shirt", size));
    }

    /**
     * 기존 인증정보와 변경 후보값을 분리한 개인 요청을 구성한다.
     */
    private RegistrationModificationRequest personalRequest(
            String birth,
            String size,
            String guardian,
            Boolean consent
    ) {
        return new RegistrationModificationRequest(
                new RegistrationAccessRequest(
                        "기존이름", "1990-01-01", PHONE, "password"
                ),
                CATEGORY_ID,
                souvenirs(size),
                "변경이름",
                PHONE,
                birth,
                GenderClass.M,
                "변경주소",
                "변경상세",
                guardian,
                consent
        );
    }

    /**
     * 같은 후보값을 최초 신청 경로에도 전달한다.
     */
    private RegistrationCreateRequest creationRequest(
            RegistrationModificationRequest request
    ) {
        return new RegistrationCreateRequest(
                request.eventCategoryId(),
                request.selectedSouvenirList(),
                "password",
                request.name(),
                request.phNum(),
                request.birth(),
                request.gender(),
                request.address(),
                request.addressDetail(),
                request.guardianName(),
                request.guardianConsent()
        );
    }

    /**
     * Access 검증이 끝난 개인 Context를 구성한다.
     */
    private RegistrationModificationAccessContext personalContext(
            Registration current,
            RegistrationModificationRequest request
    ) {
        return new RegistrationModificationAccessContext(
                event, current, request, NOW
        );
    }

    /**
     * 단체 최종목록에 들어갈 참가자를 구성한다.
     */
    private OrgRegistrationModificationParticipantRequest participant(
            String id,
            String name,
            String birth,
            String size
    ) {
        return new OrgRegistrationModificationParticipantRequest(
                id, CATEGORY_ID, souvenirs(size),
                name, PHONE, birth, GenderClass.M
        );
    }

    /**
     * Access 검증이 끝난 단체 Context를 구성한다.
     */
    private OrgRegistrationModificationAccessContext groupContext(
            Organization organization,
            List<Registration> current,
            List<OrgRegistrationModificationParticipantRequest> participants
    ) {
        return new OrgRegistrationModificationAccessContext(
                event,
                organization,
                current,
                new OrgRegistrationModificationRequest(
                        new OrganizationAccessRequest("group-login", "password"),
                        participants
                ),
                NOW
        );
    }

    /**
     * DB에서 조회한 단체와 같은 문자열 생년월일 구조를 사용한다.
     */
    private Organization organization(String birth, boolean consent) {
        return Organization.builder()
                .id("organization")
                .event(event)
                .loginId("group-login")
                .password("password")
                .leaderName("단체장")
                .leaderBirth(birth)
                .guardianConsent(consent)
                .build();
    }

    /**
     * 변경 여부를 확인할 실제 Registration 객체를 구성한다.
     */
    private Registration existing(String id, Organization organization) {
        return Registration.builder()
                .id(id)
                .event(event)
                .eventCategory(category)
                .organization(organization)
                .name("기존이름")
                .phNum(PHONE)
                .birth("1990-01-01")
                .password("password")
                .gender(GenderClass.M)
                .address("기존주소")
                .addressDetail("기존상세")
                .guardianName(null)
                .guardianConsent(false)
                .souvenirJson(souvenirs("M"))
                .contractAmount(new BigDecimal("70000"))
                .paidAmount(BigDecimal.ZERO)
                .status(RegistrationStatus.PAYMENT_PENDING)
                .build();
    }

    /**
     * 후보 검증 전후 비교를 위해 변경 가능한 주요 신청값을 복사한다.
     */
    private RegistrationSnapshot snapshot(Registration registration) {
        return new RegistrationSnapshot(
                registration.getName(),
                registration.getPhNum(),
                registration.getBirth(),
                registration.getGender(),
                registration.getAddress(),
                registration.getAddressDetail(),
                registration.getGuardianName(),
                registration.isGuardianConsent(),
                registration.getEventCategory(),
                List.copyOf(registration.getSouvenirJson()),
                registration.getContractAmount(),
                registration.getPaidAmount(),
                registration.getStatus(),
                registration.isSoftDeleted()
        );
    }

    /**
     * 검증 중 신청값과 금융 summary가 변경되지 않았는지 비교하는 값 객체다.
     */
    private record RegistrationSnapshot(
            String name,
            String phNum,
            String birth,
            GenderClass gender,
            String address,
            String addressDetail,
            String guardianName,
            boolean guardianConsent,
            EventCategory category,
            List<SouvenirJson> souvenirs,
            BigDecimal contractAmount,
            BigDecimal paidAmount,
            RegistrationStatus status,
            boolean softDeleted
    ) {
    }

    /**
     * 실패 시 예상한 도메인 오류가 반환되는지 확인한다.
     */
    private void expectError(ErrorCode expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(CustomException.class)
                .satisfies(exception ->
                        assertThat(
                                ((CustomException) exception).getErrorCode()
                        ).isEqualTo(expected)
                );
    }
}
