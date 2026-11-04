package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ProductType;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 개인 수정의 주소 필수·상품 종목 변경 제한과 단품 참가 항목 동기화를 확인한다. */
@ExtendWith(MockitoExtension.class)
class RegistrationProductModificationServiceTest {

    @Mock
    private EventRegistrationPolicyRepository policies;
    @Mock
    private EventCategoryCommandRepository categories;
    @Mock
    private RegistrationEntryCommandRepository entries;

    private RegistrationProductModificationService service;

    private final Event event = Event.builder().id("event").build();
    private final EventCategory single = EventCategory.builder()
            .id("avengers").event(event).productType(ProductType.SINGLE).parentCategoryId("package").build();

    @BeforeEach
    void setUp() {
        service = new RegistrationProductModificationService(policies, categories, entries, new RegistrationPolicyValidator());
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
        when(categories.findById("package")).thenReturn(Optional.of(
                EventCategory.builder().id("package").event(event).productType(ProductType.PACKAGE).build()));

        expectError(ErrorCode.REGISTRATION_PRODUCT_CHANGE_NOT_ALLOWED,
                () -> service.validate(registration(single), request("package", "주소")));
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

    /** 단품의 참가 항목은 바뀐 신청자 값을 따르고 배번은 그대로 둔다. */
    @Test
    void syncsSingleEntryWithApplicant() {
        RegistrationEntry entry = RegistrationEntry.builder().event(event).eventCategory(single)
                .name("기존").phNum("010-0000-0000").birth("1990-01-01").gender(GenderClass.M).bibNumber("10001").build();
        when(entries.findByRegistration_Id("r1")).thenReturn(List.of(entry));

        service.syncApplicantEntry(registration(single), request("avengers", "주소"));

        assertThat(entry.getName()).isEqualTo("변경이름");
        assertThat(entry.getPhNum()).isEqualTo("010-9999-9999");
        assertThat(entry.getBirth()).isEqualTo("1991-02-03");
        assertThat(entry.getGender()).isEqualTo(GenderClass.F);
        assertThat(entry.getBibNumber()).isEqualTo("10001");
    }

    /** 단품인데 참가 항목이 없으면 바뀐 신청자 값으로 항목을 만든다. */
    @Test
    void createsMissingSingleEntry() {
        Registration registration = registration(single);
        when(entries.findByRegistration_Id("r1")).thenReturn(List.of());

        service.syncApplicantEntry(registration, request("avengers", "주소"));

        ArgumentCaptor<RegistrationEntry> saved = ArgumentCaptor.forClass(RegistrationEntry.class);
        verify(entries).save(saved.capture());
        assertThat(saved.getValue().getRegistration()).isSameAs(registration);
        assertThat(saved.getValue().getEventCategory()).isSameAs(single);
        assertThat(saved.getValue().getName()).isEqualTo("변경이름");
        assertThat(saved.getValue().getBirth()).isEqualTo("1991-02-03");
    }

    /** 패키지·기존 종목은 신청자 수정으로 항목을 바꾸지 않는다. */
    @Test
    void doesNotSyncNonSingleEntries() {
        Registration registration = registration(
                EventCategory.builder().id("package").event(event).productType(ProductType.PACKAGE).build());

        service.syncApplicantEntry(registration, request("package", "주소"));

        verifyNoInteractions(entries);
    }

    private void addressRequired(boolean required) {
        EventRegistrationPolicy policy = mock(EventRegistrationPolicy.class);
        when(policy.getAddressRequired()).thenReturn(required);
        when(policies.findByEventId("event")).thenReturn(Optional.of(policy));
    }

    private Registration registration(EventCategory category) {
        return Registration.builder().id("r1").event(event).eventCategory(category).build();
    }

    private static RegistrationModificationRequest request(String categoryId, String address) {
        return new RegistrationModificationRequest(
                new RegistrationAccessRequest("기존", "1990-01-01", "010-0000-0000", "password"),
                categoryId, List.of(), "변경이름", "010-9999-9999", "1991-02-03", GenderClass.F,
                address, null, false, null, null, null, null, "group", true);
    }

    private static void expectError(ErrorCode errorCode, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
