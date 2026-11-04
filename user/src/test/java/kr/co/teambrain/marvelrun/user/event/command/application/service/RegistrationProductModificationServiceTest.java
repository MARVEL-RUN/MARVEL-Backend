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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 개인 수정의 주소 필수·상품 종목 변경 제한과 단품 참가 항목 동기화를 확인한다. */
class RegistrationProductModificationServiceTest {

    private final EventRegistrationPolicyRepository policies = mock(EventRegistrationPolicyRepository.class);
    private final EventCategoryCommandRepository categories = mock(EventCategoryCommandRepository.class);
    private final RegistrationEntryCommandRepository entries = mock(RegistrationEntryCommandRepository.class);
    private final RegistrationProductModificationService service = new RegistrationProductModificationService(
            policies, categories, entries, new RegistrationPolicyValidator());

    private final Event event = Event.builder().id("event").build();
    private EventCategory single;
    private Registration registration;
    private EventRegistrationPolicy policy;

    @BeforeEach
    void setUp() {
        single = EventCategory.builder().id("avengers").productType(ProductType.SINGLE).parentCategoryId("package").build();
        registration = mock(Registration.class);
        when(registration.getId()).thenReturn("r1");
        when(registration.getEvent()).thenReturn(event);
        when(registration.getEventCategory()).thenReturn(single);
        policy = mock(EventRegistrationPolicy.class);
        when(policy.getAddressRequired()).thenReturn(true);
        when(policies.findByEventId("event")).thenReturn(Optional.of(policy));
    }

    /** 주소 필수 대회에서는 수정으로 주소를 지울 수 없다. */
    @Test
    void rejectsBlankAddressWhenPolicyRequiresIt() {
        expectError(ErrorCode.REGISTRATION_ADDRESS_REQUIRED, () -> service.validate(registration, request("avengers", " ")));
        assertThatCode(() -> service.validate(registration, request("avengers", "주소"))).doesNotThrowAnyException();
    }

    /** 참가 항목이 있는 상품은 개인 수정으로 종목을 바꾸지 않는다(상향은 전용 흐름). */
    @Test
    void rejectsCategoryChangeOfProduct() {
        when(categories.findById("package")).thenReturn(Optional.of(
                EventCategory.builder().id("package").productType(ProductType.PACKAGE).build()));

        expectError(ErrorCode.REGISTRATION_PRODUCT_CHANGE_NOT_ALLOWED,
                () -> service.validate(registration, request("package", "주소")));
    }

    /** 기존 종목에서 상품 종목으로 옮기는 것도 막는다. 기존 종목끼리는 그대로 바꿀 수 있다. */
    @Test
    void rejectsChangeIntoProductButAllowsLegacyChange() {
        EventCategory legacy = EventCategory.builder().id("10km").build();
        when(registration.getEventCategory()).thenReturn(legacy);
        when(policy.getAddressRequired()).thenReturn(false);
        when(categories.findById("avengers")).thenReturn(Optional.of(single));
        when(categories.findById("5km")).thenReturn(Optional.of(EventCategory.builder().id("5km").build()));

        expectError(ErrorCode.REGISTRATION_PRODUCT_CHANGE_NOT_ALLOWED,
                () -> service.validate(registration, request("avengers", null)));
        assertThatCode(() -> service.validate(registration, request("5km", null))).doesNotThrowAnyException();
    }

    /** 단품의 참가 항목은 바뀐 신청자 값을 따르고 배번은 그대로 둔다. */
    @Test
    void syncsSingleEntryWithApplicant() {
        RegistrationEntry entry = RegistrationEntry.builder().event(event).eventCategory(single)
                .name("기존").phNum("010-0000-0000").birth("1990-01-01").gender(GenderClass.M).bibNumber("10001").build();
        when(entries.findByRegistration_Id("r1")).thenReturn(List.of(entry));

        service.syncApplicantEntry(registration, request("avengers", "주소"));

        assertThat(entry.getName()).isEqualTo("변경이름");
        assertThat(entry.getPhNum()).isEqualTo("010-9999-9999");
        assertThat(entry.getBirth()).isEqualTo("1991-02-03");
        assertThat(entry.getGender()).isEqualTo(GenderClass.F);
        assertThat(entry.getBibNumber()).isEqualTo("10001");
    }

    /** 패키지·기존 종목은 신청자 수정으로 항목을 바꾸지 않는다. */
    @Test
    void doesNotSyncNonSingleEntries() {
        when(registration.getEventCategory()).thenReturn(
                EventCategory.builder().id("package").productType(ProductType.PACKAGE).build());

        service.syncApplicantEntry(registration, request("package", "주소"));

        verifyNoInteractions(entries);
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
