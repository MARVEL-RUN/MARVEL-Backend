package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ProductType;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationEntry;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.policy.EventRegistrationPolicy;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationPolicyValidator;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategoryCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventRegistrationPolicyRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationEntryCommandRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 개인 수정의 주소 필수·상품 변경 제한과, 신청자 정보를 바꿔도 참가 항목은 그대로 두는지 확인한다. */
@ExtendWith(MockitoExtension.class)
class RegistrationProductModificationServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    private static final List<SouvenirJson> KIT = List.of(new SouvenirJson("kit", "FREE"));

    @Mock
    private EventRegistrationPolicyRepository policies;
    @Mock
    private EventCategoryCommandRepository categories;
    @Mock
    private RegistrationEntryCommandRepository entries;

    private RegistrationProductModificationService service;

    private final Event event = Event.builder().id("event").startDate(LocalDateTime.of(2026, 12, 1, 9, 0)).build();
    private final EventCategory single = EventCategory.builder()
            .id("avengers").event(event).productType(ProductType.SINGLE).parentCategoryId("package").build();

    @BeforeEach
    void setUp() {
        service = new RegistrationProductModificationService(policies, categories, entries, new RegistrationPolicyValidator(),
                new RegistrationModificationClassifier());
    }

    /** 주소 필수 대회에서는 수정으로 주소를 지울 수 없다. 주소가 있으면 정책을 읽지 않는다. */
    @Test
    void rejectsBlankAddressWhenPolicyRequiresIt() {
        addressRequired(true);
        Registration registration = registration(single);

        expectError(ErrorCode.REGISTRATION_ADDRESS_REQUIRED, () -> service.validate(registration, request("avengers", " ")));
        assertThatCode(() -> service.validate(registration, request("avengers", "주소"))).doesNotThrowAnyException();
    }

    /** 참가 항목이 있는 상품은 개인 수정으로 종목을 바꾸지 않는다(상향은 전용 흐름). */
    @Test
    void rejectsCategoryChangeOfProduct() {
        expectError(ErrorCode.REGISTRATION_PRODUCT_CHANGE_NOT_ALLOWED,
                () -> service.validate(registration(single), request("package", "주소")));
    }

    /** 상품 신청은 같은 종목이어도 기념품을 바꾸지 않는다. 대표 줄 하나로 정원을 다시 계산하면 틀린다. */
    @Test
    void rejectsSouvenirChangeOfProduct() {
        RegistrationModificationRequest changed = request("avengers", "주소", List.of(new SouvenirJson("kit", "L")));

        expectError(ErrorCode.REGISTRATION_PRODUCT_CHANGE_NOT_ALLOWED, () -> service.validate(registration(single), changed));
    }

    /** 기존 종목에서 상품 종목으로 옮기는 것도 막는다. 기존 종목끼리는 그대로 바꿀 수 있다. */
    @Test
    void rejectsChangeIntoProductButAllowsLegacyChange() {
        addressRequired(false);
        Registration registration = registration(EventCategory.builder().id("10km").event(event).build());
        when(categories.findById("avengers")).thenReturn(Optional.of(single));
        when(categories.findById("5km")).thenReturn(Optional.of(EventCategory.builder().id("5km").event(event).build()));

        expectError(ErrorCode.REGISTRATION_PRODUCT_CHANGE_NOT_ALLOWED,
                () -> service.validate(registration, request("avengers", null)));
        assertThatCode(() -> service.validate(registration, request("5km", null))).doesNotThrowAnyException();
    }

    /** 다른 대회의 종목은 상품 여부를 보지 않고 넘겨, 뒤의 기존 종목 검증이 거절하게 둔다. */
    @Test
    void leavesOtherEventCategoryToExistingValidation() {
        Registration registration = registration(EventCategory.builder().id("10km").event(event).build());
        Event otherEvent = Event.builder().id("other").build();
        when(categories.findById("other-package")).thenReturn(Optional.of(
                EventCategory.builder().id("other-package").event(otherEvent).productType(ProductType.PACKAGE).build()));

        assertThatCode(() -> service.validate(registration, request("other-package", "주소"))).doesNotThrowAnyException();
    }

    /** 신청자 생년월일 변경을 검사해도 참가 항목(받는 사람)은 그대로다. 신청 행 반영은 개인정보 수정이 한다. */
    @Test
    void applicantBirthChangeDoesNotTouchEntries() {
        guardianPolicy();
        RegistrationEntry entry = RegistrationEntry.builder().event(event).eventCategory(single)
                .name("아이").phNum("010-0000-0000").birth("2001-01-01").gender(GenderClass.M).bibNumber("10001").build();
        when(entries.findByRegistration_Id("r1")).thenReturn(List.of(entry));
        Registration registration = registration(single);

        service.validateApplicantChange(registration, request("avengers", "주소"), TODAY);

        assertThat(registration.getBirth()).isEqualTo("1990-01-01");
        assertThat(entry.getName()).isEqualTo("아이");
        assertThat(entry.getPhNum()).isEqualTo("010-0000-0000");
        assertThat(entry.getBirth()).isEqualTo("2001-01-01");
        assertThat(entry.getGender()).isEqualTo(GenderClass.M);
        assertThat(entry.getBibNumber()).isEqualTo("10001");
        verify(entries, never()).save(any());
    }

    /** 보호자는 신청에 하나이므로 14세 미만 받는 사람이 있으면 신청자가 성인이어도 보호자를 지울 수 없다. */
    @Test
    void guardianRecheckCoversEntries() {
        guardianPolicy();
        RegistrationEntry child = RegistrationEntry.builder().event(event).eventCategory(single)
                .name("아이").phNum("010-0000-0000").birth("2016-01-01").gender(GenderClass.M).build();
        when(entries.findByRegistration_Id("r1")).thenReturn(List.of(child));
        Registration registration = Registration.builder().id("r1").event(event).eventCategory(single)
                .souvenirJson(KIT).birth("1990-01-01").guardianName("보호자").guardianConsent(true).build();

        expectError(ErrorCode.GUARDIAN_NAME_REQUIRED,
                () -> service.validateApplicantChange(registration, request("avengers", "주소"), TODAY));
        assertThat(registration.getBirth()).isEqualTo("1990-01-01");
    }

    /** 생년월일·보호자가 그대로면 정책도 항목도 읽지 않는다. */
    @Test
    void unchangedApplicantSkipsGuardianRecheck() {
        Registration registration = Registration.builder().id("r1").event(event).eventCategory(single)
                .souvenirJson(KIT).birth("1991-02-03").build();

        service.validateApplicantChange(registration, request("avengers", "주소"), TODAY);

        verifyNoInteractions(policies, entries);
    }

    private void addressRequired(boolean required) {
        EventRegistrationPolicy policy = mock(EventRegistrationPolicy.class);
        when(policy.getAddressRequired()).thenReturn(required);
        when(policies.findByEventId("event")).thenReturn(Optional.of(policy));
    }

    private void guardianPolicy() {
        EventRegistrationPolicy policy = mock(EventRegistrationPolicy.class);
        when(policy.getEvent()).thenReturn(event);
        when(policy.getGuardianRequiredBirthFrom()).thenReturn(LocalDate.of(2012, 11, 6));
        when(policies.findByEventId("event")).thenReturn(Optional.of(policy));
    }

    private Registration registration(EventCategory category) {
        return Registration.builder().id("r1").event(event).eventCategory(category).souvenirJson(KIT)
                .birth("1990-01-01").build();
    }

    private static RegistrationModificationRequest request(String categoryId, String address) {
        return request(categoryId, address, KIT);
    }

    private static RegistrationModificationRequest request(String categoryId, String address, List<SouvenirJson> souvenirs) {
        return new RegistrationModificationRequest(
                new RegistrationAccessRequest("기존", "1990-01-01", "010-0000-0000", "password"),
                categoryId, souvenirs, "변경이름", "010-9999-9999", "1991-02-03", GenderClass.F,
                address, null, false, null, null, null, null, "group", true);
    }

    private static void expectError(ErrorCode errorCode, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
