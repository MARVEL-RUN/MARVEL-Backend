package kr.co.teambrain.marvelrun.admin.event.command.application.service;

import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.admin.event.command.application.context.RegistrationPolicyContext;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.RegistrationEntry;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.policy.EventCategoryRegistrationPolicy;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.policy.EventRegistrationPolicy;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.RegistrationEntryModifyRequest;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.RegistrationEntryModifyRequest.Entry;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.RegistrationEntryModifyResponse;
import kr.co.teambrain.marvelrun.admin.event.command.application.valid.RegistrationPolicyValidator;
import kr.co.teambrain.marvelrun.admin.event.command.application.valid.loader.RegistrationPolicyLoader;
import kr.co.teambrain.marvelrun.admin.event.command.repository.RegistrationCommandRepository;
import kr.co.teambrain.marvelrun.admin.event.command.repository.RegistrationEntryCommandRepository;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 관리자 패키지 받는 사람 수정의 대상 검증, 보호자·종목 생년월일 정책, 배번 유지를 확인한다. */
@ExtendWith(MockitoExtension.class)
class RegistrationEntryCommandServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 25, 10, 0);

    @Mock RegistrationCommandRepository registrations;
    @Mock RegistrationEntryCommandRepository entryRepository;
    @Mock RegistrationPolicyLoader policyLoader;
    @Mock ServerTimeProvider time;

    private RegistrationEntryCommandService service;
    private Event event;
    private EventCategory pkg;
    private EventCategory catA;
    private EventCategory catB;
    private RegistrationEntry entryA;
    private RegistrationEntry entryB;
    private EventRegistrationPolicy eventPolicy;
    private EventCategoryRegistrationPolicy policyA;

    @BeforeEach
    void setUp() {
        service = new RegistrationEntryCommandService(registrations, entryRepository, policyLoader,
                new RegistrationPolicyValidator(), time);
        lenient().when(time.currentDateTime()).thenReturn(NOW);
        event = mock(Event.class);
        lenient().when(event.getId()).thenReturn("ev");
        pkg = category("pkg", "패키지", ProductType.PACKAGE);
        catA = category("a", "A", ProductType.SINGLE);
        catB = category("b", "B", ProductType.SINGLE);
        eventPolicy = mock(EventRegistrationPolicy.class);
        lenient().when(eventPolicy.getEvent()).thenReturn(event);
        lenient().when(eventPolicy.getGuardianRequiredBirthFrom()).thenReturn(LocalDate.of(2013, 11, 1));
        policyA = categoryPolicy(catA, null, null);
        EventCategoryRegistrationPolicy policyB = categoryPolicy(catB, null, null);
        lenient().when(policyLoader.load(eq("ev"), any(), eq(Set.of()))).thenReturn(
                new RegistrationPolicyContext(eventPolicy, Map.of("a", policyA, "b", policyB), Map.of()));
    }

    /** 요청에 담은 항목만 바뀌고 배번과 나머지 항목은 그대로이며 메모에 기록이 남는다. */
    @Test
    void updatesOnlyListedEntriesAndKeepsBib() {
        Registration registration = registration(pkg, false, null, false);
        stubEntries(registration);

        RegistrationEntryModifyResponse response = service.modifyEntries("r",
                request(new Entry("ea", "새이름", "01011112222", "1991-02-03", GenderClass.F)));

        assertThat(entryA.getName()).isEqualTo("새이름");
        assertThat(entryA.getPhNum()).isEqualTo("01011112222");
        assertThat(entryA.getBirth()).isEqualTo("1991-02-03");
        assertThat(entryA.getGender()).isEqualTo(GenderClass.F);
        assertThat(entryA.getBibNumber()).isEqualTo("10001");
        assertThat(entryB.getName()).isEqualTo("원래B");
        assertThat(response.entries()).singleElement().satisfies(e -> {
            assertThat(e.entryId()).isEqualTo("ea");
            assertThat(e.eventCategoryId()).isEqualTo("a");
            assertThat(e.categoryName()).isEqualTo("A");
            assertThat(e.bibNumber()).isEqualTo("10001");
        });
        assertThat(registration.getDetailMemo()).contains("관리자에 의한 패키지 받는 사람 정보 수정 (1명)");
    }

    /** 단품 신청은 신청자 정보를 따르므로 거절한다. */
    @Test
    void rejectsSingleRegistration() {
        stubEntries(registration(catA, false, null, false));

        assertError(ErrorCode.REGISTRATION_ENTRY_PACKAGE_ONLY, request(valid("ea")));
    }

    /** 다른 신청의 참가 항목 id는 거절한다. */
    @Test
    void rejectsForeignEntry() {
        stubEntries(registration(pkg, false, null, false));

        assertError(ErrorCode.INVALID_REGISTRATION_ENTRIES, request(valid("other")));
    }

    /** 같은 참가 항목 id가 두 번 오면 거절한다. */
    @Test
    void rejectsDuplicateEntryId() {
        stubEntries(registration(pkg, false, null, false));

        assertError(ErrorCode.REGISTRATION_ENTRY_DUPLICATED, request(valid("ea"), valid("ea")));
    }

    /** 삭제된 신청은 거절한다. */
    @Test
    void rejectsDeletedRegistration() {
        stubEntries(registration(pkg, true, null, false));

        assertError(ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET, request(valid("ea")));
    }

    /** 없는 신청은 거절한다. */
    @Test
    void rejectsMissingRegistration() {
        when(registrations.findById("r")).thenReturn(Optional.empty());

        assertError(ErrorCode.REGISTRATION_NOT_FOUND, request(valid("ea")));
    }

    /** 보호자 동의 대상 나이의 받는 사람은 신청에 보호자 동의가 없으면 거절한다. */
    @Test
    void rejectsChildWithoutStoredGuardian() {
        stubEntries(registration(pkg, false, null, false));

        assertError(ErrorCode.GUARDIAN_NAME_REQUIRED, request(child("ea")));
    }

    /** 보호자 이름은 있어도 동의가 없으면 거절한다. */
    @Test
    void rejectsChildWithoutStoredConsent() {
        stubEntries(registration(pkg, false, "보호자", false));

        assertError(ErrorCode.GUARDIAN_CONSENT_REQUIRED, request(child("ea")));
    }

    /** 신청에 보호자 이름과 동의가 저장돼 있으면 어린 받는 사람도 수정한다. */
    @Test
    void acceptsChildWithStoredGuardian() {
        stubEntries(registration(pkg, false, "보호자", true));

        service.modifyEntries("r", request(child("ea")));

        assertThat(entryA.getBirth()).isEqualTo("2015-05-05");
    }

    /** 종목의 허용 출생일 범위를 벗어나면 거절한다. */
    @Test
    void rejectsCategoryBirthViolation() {
        stubEntries(registration(pkg, false, null, false));
        when(policyA.getAllowedBirthTo()).thenReturn(LocalDate.of(1980, 12, 31));

        assertError(ErrorCode.REGISTRATION_CATEGORY_BIRTH_NOT_ALLOWED, request(valid("ea")));
    }

    private void assertError(ErrorCode code, RegistrationEntryModifyRequest request) {
        assertThatThrownBy(() -> service.modifyEntries("r", request))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private void stubEntries(Registration registration) {
        lenient().when(registrations.findById("r")).thenReturn(Optional.of(registration));
        entryA = entry("ea", registration, catA, "원래A", "10001");
        entryB = entry("eb", registration, catB, "원래B", "20001");
        lenient().when(entryRepository.findByRegistration_Id("r")).thenReturn(List.of(entryA, entryB));
    }

    private Registration registration(EventCategory category, boolean deleted, String guardianName, boolean consent) {
        return Registration.builder().id("r").event(event).eventCategory(category).softDeleted(deleted)
                .guardianName(guardianName).guardianConsent(consent)
                .name("신청자").phNum("01000000000").birth("1990-01-01").gender(GenderClass.M).build();
    }

    private RegistrationEntry entry(String id, Registration registration, EventCategory category, String name, String bib) {
        return RegistrationEntry.builder().id(id).event(event).registration(registration).eventCategory(category)
                .name(name).phNum("01000000000").birth("1990-01-01").gender(GenderClass.M).bibNumber(bib).build();
    }

    private EventCategory category(String id, String name, ProductType type) {
        EventCategory category = mock(EventCategory.class);
        lenient().when(category.getId()).thenReturn(id);
        lenient().when(category.getName()).thenReturn(name);
        lenient().when(category.getProductType()).thenReturn(type);
        return category;
    }

    private EventCategoryRegistrationPolicy categoryPolicy(EventCategory category, LocalDate from, LocalDate to) {
        EventCategoryRegistrationPolicy policy = mock(EventCategoryRegistrationPolicy.class);
        lenient().when(policy.getEventCategory()).thenReturn(category);
        lenient().when(policy.getAllowedBirthFrom()).thenReturn(from);
        lenient().when(policy.getAllowedBirthTo()).thenReturn(to);
        return policy;
    }

    private static Entry valid(String entryId) {
        return new Entry(entryId, "새이름", "01011112222", "1985-05-05", GenderClass.M);
    }

    private static Entry child(String entryId) {
        return new Entry(entryId, "아이", "01011112222", "2015-05-05", GenderClass.F);
    }

    private static RegistrationEntryModifyRequest request(Entry... entries) {
        return new RegistrationEntryModifyRequest(List.of(entries));
    }
}
