package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ProductType;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationEntryDraft;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.policy.EventCategoryRegistrationPolicy;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.policy.EventRegistrationPolicy;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationEntryRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.dto.RegistrationPolicyInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 상품 종류별 참가 항목 판정을 실제 정책 검증기와 함께 확인한다. 저장소는 쓰지 않는다. */
class RegistrationEntryValidatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);

    private final RegistrationPolicyValidator policyValidator = new RegistrationPolicyValidator();
    private final RegistrationEntryValidator validator = new RegistrationEntryValidator(policyValidator);

    private Event event;
    private EventCategory category;
    private EventRegistrationPolicy eventPolicy;
    private List<EventCategory> components;
    private Map<String, EventCategoryRegistrationPolicy> componentPolicies;

    private final RegistrationEntryDraft applicant =
            new RegistrationEntryDraft(null, "신청자", "010-1111-2222", "1990-01-01", GenderClass.M);

    @BeforeEach
    void setUp() {
        event = mock(Event.class);
        when(event.getId()).thenReturn("event");
        category = mock(EventCategory.class);
        when(category.getId()).thenReturn("category");
        eventPolicy = mock(EventRegistrationPolicy.class);
        when(eventPolicy.getEvent()).thenReturn(event);
        when(eventPolicy.getGuardianRequiredBirthFrom()).thenReturn(LocalDate.of(2012, 11, 6));
        components = List.of(component("avengers"), component("xmen"), component("fantastic4"));
        componentPolicies = new HashMap<>();
        for (EventCategory component : components) {
            componentPolicies.put(component.getId(), unrestricted(component));
        }
    }

    /** 상품 종류가 없는 종목은 항목을 만들지 않고, 항목 입력을 받지 않는다. */
    @Test
    void noProductTypeCreatesNoEntriesAndRejectsEntries() {
        assertThat(validate(List.of(), null, null)).isEmpty();
        expectEntriesError(() -> validate(List.of(), List.of(entry("avengers", "1990-01-01")), null));
    }

    /** 단품은 그 종목과 신청자 값으로 항목 하나를 만든다. */
    @Test
    void singleCreatesApplicantEntry() {
        single();

        assertThat(validate(List.of(), List.of(), null)).containsExactly(
                new RegistrationEntryDraft(category, "신청자", "010-1111-2222", "1990-01-01", GenderClass.M));
    }

    /** 단품은 받는 사람 입력을 받지 않는다. */
    @Test
    void singleRejectsEntries() {
        single();

        expectEntriesError(() -> validate(List.of(), List.of(entry("xmen", "1990-01-01")), null));
    }

    /** 패키지는 하위 종목마다 받는 사람으로 항목을 만든다. */
    @Test
    void packageCreatesEntryPerChildCategory() {
        packageProduct();

        List<RegistrationEntryDraft> entries = validate(components, List.of(
                entry("xmen", "1991-01-01"), entry("avengers", "1992-01-01"), entry("fantastic4", "1993-01-01")), null);

        assertThat(entries).extracting(entry -> entry.eventCategory().getId())
                .containsExactly("xmen", "avengers", "fantastic4");
        assertThat(entries).extracting(RegistrationEntryDraft::birth)
                .containsExactly("1991-01-01", "1992-01-01", "1993-01-01");
    }

    /** 하위 종목이 빠지거나 겹치거나 하위 종목이 아니면 거절한다. */
    @Test
    void packageRejectsMissingDuplicatedOrUnknownChildren() {
        packageProduct();

        expectEntriesError(() -> validate(components, null, null));
        expectEntriesError(() -> validate(components, List.of(
                entry("xmen", "1991-01-01"), entry("avengers", "1992-01-01")), null));
        expectEntriesError(() -> validate(components, List.of(
                entry("xmen", "1991-01-01"), entry("xmen", "1992-01-01"), entry("fantastic4", "1993-01-01")), null));
        expectEntriesError(() -> validate(components, List.of(
                entry("xmen", "1991-01-01"), entry("avengers", "1992-01-01"), entry("category", "1993-01-01")), null));
    }

    /** 하위 종목이 없거나 단품이 아닌 하위 종목이 있으면 설정 오류다. */
    @Test
    void packageWithoutValidChildrenIsConfigurationError() {
        packageProduct();

        expectError(ErrorCode.REGISTRATION_POLICY_CONFIGURATION_ERROR,
                () -> validate(List.of(), List.of(entry("xmen", "1991-01-01")), null));
        EventCategory option = component("option");
        when(option.getProductType()).thenReturn(ProductType.OPTION);
        expectError(ErrorCode.REGISTRATION_POLICY_CONFIGURATION_ERROR,
                () -> validate(List.of(option), List.of(entry("option", "1991-01-01")), null));
    }

    /** 받는 사람이 보호자 기준에 걸리면 신청의 보호자 입력이 있어야 한다. */
    @Test
    void packageRecipientUnderGuardianAgeRequiresGuardian() {
        packageProduct();
        List<RegistrationEntryRequest> entries = List.of(
                entry("xmen", "2015-01-01"), entry("avengers", "1992-01-01"), entry("fantastic4", "1993-01-01"));

        expectError(ErrorCode.GUARDIAN_NAME_REQUIRED, () -> validate(components, entries, null));
        assertThat(validator.validate(event, category, components, applicant, entries, eventPolicy, componentPolicies,
                new RegistrationPolicyInput("1990-01-01", "보호자", true), TODAY)).hasSize(3);
    }

    /** 받는 사람의 생년월일 형식도 신청자와 같은 기준으로 본다. */
    @Test
    void packageRecipientBirthMustBeValid() {
        packageProduct();

        expectError(ErrorCode.INVALID_REGISTRATION_BIRTH, () -> validate(components, List.of(
                entry("xmen", "1991-13-01"), entry("avengers", "1992-01-01"), entry("fantastic4", "1993-01-01")), null));
    }

    /** 받는 사람은 패키지가 아니라 자기 하위 종목의 출생일 정책을 따른다. */
    @Test
    void packageRecipientFollowsChildCategoryBirthPolicy() {
        packageProduct();
        EventCategoryRegistrationPolicy adultsOnly = mock(EventCategoryRegistrationPolicy.class);
        when(adultsOnly.getEventCategory()).thenReturn(components.get(1));
        when(adultsOnly.getAllowedBirthTo()).thenReturn(LocalDate.of(2000, 12, 31));
        componentPolicies.put("xmen", adultsOnly);

        expectError(ErrorCode.REGISTRATION_CATEGORY_BIRTH_NOT_ALLOWED, () -> validate(components, List.of(
                entry("xmen", "2005-01-01"), entry("avengers", "1992-01-01"), entry("fantastic4", "1993-01-01")), null));
        assertThat(validate(components, List.of(
                entry("xmen", "1999-01-01"), entry("avengers", "2005-01-01"), entry("fantastic4", "1993-01-01")), null))
                .hasSize(3);
    }

    /** 옵션 상품은 단독으로 신청하지 않는다. */
    @Test
    void optionCannotBePurchasedAlone() {
        when(category.getProductType()).thenReturn(ProductType.OPTION);

        expectError(ErrorCode.PRODUCT_NOT_PURCHASABLE_ALONE, () -> validate(List.of(), null, null));
    }

    private List<RegistrationEntryDraft> validate(
            List<EventCategory> children, List<RegistrationEntryRequest> entries, String guardianName) {
        return validator.validate(event, category, children, applicant, entries, eventPolicy, componentPolicies,
                new RegistrationPolicyInput("1990-01-01", guardianName, null), TODAY);
    }

    private void single() {
        when(category.getProductType()).thenReturn(ProductType.SINGLE);
    }

    private EventCategory component(String id) {
        EventCategory component = mock(EventCategory.class);
        when(component.getId()).thenReturn(id);
        when(component.getProductType()).thenReturn(ProductType.SINGLE);
        when(component.getParentCategoryId()).thenReturn("category");
        return component;
    }

    private void packageProduct() {
        when(category.getProductType()).thenReturn(ProductType.PACKAGE);
    }

    private static EventCategoryRegistrationPolicy unrestricted(EventCategory category) {
        EventCategoryRegistrationPolicy policy = mock(EventCategoryRegistrationPolicy.class);
        when(policy.getEventCategory()).thenReturn(category);
        return policy;
    }

    private static RegistrationEntryRequest entry(String categoryId, String birth) {
        return new RegistrationEntryRequest(categoryId, "받는사람-" + categoryId, "010-3333-4444", birth, GenderClass.F);
    }

    private static void expectEntriesError(Executable executable) {
        expectError(ErrorCode.INVALID_REGISTRATION_ENTRIES, executable);
    }

    private static void expectError(ErrorCode errorCode, Executable executable) {
        assertThatThrownBy(executable::execute)
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
    }
}
