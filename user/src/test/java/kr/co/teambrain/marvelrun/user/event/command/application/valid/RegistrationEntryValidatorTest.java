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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 상품 종류별(구매 줄별) 참가 항목 판정을 실제 정책 검증기와 함께 확인한다. 저장소는 쓰지 않는다.
 *
 * 정책 mock은 그 정책까지 검증이 닿는 테스트에서만 만든다. 닿기 전에 거절되는 경우에는 정책을 넘기지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class RegistrationEntryValidatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);

    private final RegistrationPolicyValidator policyValidator = new RegistrationPolicyValidator();
    private final RegistrationEntryValidator validator = new RegistrationEntryValidator(policyValidator);

    private final Event event = Event.builder().id("event").build();
    private final List<EventCategory> components =
            List.of(component("avengers"), component("xmen"), component("fantastic4"));

    /** 상품 종류가 없는 종목은 최상위로 신청하고 받는 사람 입력을 받지 않는다. */
    @Test
    void noProductTypeAcceptsNoEntries() {
        EventCategory category = category(null);

        assertThatCode(() -> validator.validateWithoutItems(category, null)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validateWithoutItems(category, List.of())).doesNotThrowAnyException();
        expectEntriesError(() -> validator.validateWithoutItems(category, List.of(entry("avengers", "1990-01-01"))));
    }

    /** 상품 종목을 최상위 종목으로 보내면 거절한다. 상품은 구매 줄로만 산다. */
    @Test
    void productCategoryWithoutItemsIsRejected() {
        expectEntriesError(() -> validator.validateWithoutItems(category(ProductType.SINGLE), null));
        expectEntriesError(() -> validator.validateWithoutItems(category(ProductType.PACKAGE), List.of(
                entry("xmen", "1991-01-01"), entry("avengers", "1992-01-01"), entry("fantastic4", "1993-01-01"))));
    }

    /** 구매 줄에 상품이 아닌 종목을 담으면 거절한다. */
    @Test
    void itemWithoutProductTypeIsRejected() {
        expectEntriesError(() -> validateItem(category(null), List.of(),
                List.of(entry("category", "1990-01-01")), null, null, Map.of()));
    }

    /** 단품 줄은 그 종목의 받는 사람 한 명으로 항목을 만든다. 신청자 값을 복사하지 않는다. */
    @Test
    void singleItemCreatesEntryFromRecipient() {
        EventCategory single = components.get(0);

        List<RegistrationEntryDraft> entries = validateItem(single, List.of(),
                List.of(entry("avengers", "2001-02-03")), null, eventPolicy(), unrestrictedPolicies("xmen", "fantastic4"));

        assertThat(entries).containsExactly(new RegistrationEntryDraft(
                single, "받는사람-avengers", "010-3333-4444", "2001-02-03", GenderClass.F));
    }

    /** 단품 줄은 받는 사람이 정확히 한 명이고 종목이 그 단품이어야 한다. */
    @Test
    void singleItemRequiresExactlyOneOwnEntry() {
        EventCategory single = components.get(0);

        expectEntriesError(() -> validateItem(single, List.of(), null, null, null, Map.of()));
        expectEntriesError(() -> validateItem(single, List.of(), List.of(), null, null, Map.of()));
        expectEntriesError(() -> validateItem(single, List.of(),
                List.of(entry("xmen", "1990-01-01")), null, null, Map.of()));
        expectEntriesError(() -> validateItem(single, List.of(),
                List.of(entry("avengers", "1990-01-01"), entry("avengers", "1991-01-01")), null, null, Map.of()));
    }

    /** 단품 받는 사람도 자기 종목의 출생일 정책과 보호자 기준을 따른다. */
    @Test
    void singleItemRecipientFollowsOwnPolicyAndGuardian() {
        EventCategory single = components.get(0);
        Map<String, EventCategoryRegistrationPolicy> policies = new HashMap<>();
        EventCategoryRegistrationPolicy adultsOnly = mock(EventCategoryRegistrationPolicy.class);
        when(adultsOnly.getEventCategory()).thenReturn(single);
        when(adultsOnly.getAllowedBirthTo()).thenReturn(LocalDate.of(2000, 12, 31));
        policies.put("avengers", adultsOnly);

        expectError(ErrorCode.REGISTRATION_CATEGORY_BIRTH_NOT_ALLOWED, () -> validateItem(single, List.of(),
                List.of(entry("avengers", "2005-01-01")), null, eventPolicy(), policies));
        expectError(ErrorCode.GUARDIAN_NAME_REQUIRED, () -> validateItem(single, List.of(),
                List.of(entry("avengers", "2015-01-01")), null, eventPolicy(), Map.of()));
        assertThat(validateItem(single, List.of(), List.of(entry("avengers", "2015-01-01")), "보호자",
                eventPolicy(), unrestrictedPolicies("xmen", "fantastic4"))).hasSize(1);
    }

    /** 패키지 줄은 하위 종목마다 받는 사람으로 항목을 만든다. */
    @Test
    void packageCreatesEntryPerChildCategory() {
        List<RegistrationEntryDraft> entries = validatePackage(List.of(
                entry("xmen", "1991-01-01"), entry("avengers", "1992-01-01"), entry("fantastic4", "1993-01-01")), null,
                unrestrictedPolicies());

        assertThat(entries).extracting(entry -> entry.eventCategory().getId())
                .containsExactly("xmen", "avengers", "fantastic4");
        assertThat(entries).extracting(RegistrationEntryDraft::birth)
                .containsExactly("1991-01-01", "1992-01-01", "1993-01-01");
    }

    /** 하위 종목이 빠지거나 겹치거나 하위 종목이 아니면 거절한다. */
    @Test
    void packageRejectsMissingDuplicatedOrUnknownChildren() {
        EventCategory category = category(ProductType.PACKAGE);

        expectEntriesError(() -> validateItem(category, components, null, null, null, Map.of()));
        expectEntriesError(() -> validateItem(category, components, List.of(
                entry("xmen", "1991-01-01"), entry("avengers", "1992-01-01")), null, null, Map.of()));
        expectEntriesError(() -> validateItem(category, components, List.of(
                entry("xmen", "1991-01-01"), entry("xmen", "1992-01-01"), entry("fantastic4", "1993-01-01")),
                null, null, Map.of()));
        expectEntriesError(() -> validateItem(category, components, List.of(
                entry("xmen", "1991-01-01"), entry("avengers", "1992-01-01"), entry("category", "1993-01-01")),
                null, null, Map.of()));
    }

    /** 하위 종목이 없거나 단품이 아닌 하위 종목이 있으면 설정 오류다. */
    @Test
    void packageWithoutValidChildrenIsConfigurationError() {
        EventCategory category = category(ProductType.PACKAGE);
        EventCategory option = EventCategory.builder()
                .id("option").productType(ProductType.OPTION).parentCategoryId("category").build();

        expectError(ErrorCode.REGISTRATION_POLICY_CONFIGURATION_ERROR, () -> validateItem(category, List.of(),
                List.of(entry("xmen", "1991-01-01")), null, null, Map.of()));
        expectError(ErrorCode.REGISTRATION_POLICY_CONFIGURATION_ERROR, () -> validateItem(category, List.of(option),
                List.of(entry("option", "1991-01-01")), null, null, Map.of()));
    }

    /** 받는 사람이 보호자 기준에 걸리면 신청의 보호자 입력이 있어야 한다. */
    @Test
    void packageRecipientUnderGuardianAgeRequiresGuardian() {
        List<RegistrationEntryRequest> entries = List.of(
                entry("xmen", "2015-01-01"), entry("avengers", "1992-01-01"), entry("fantastic4", "1993-01-01"));

        expectError(ErrorCode.GUARDIAN_NAME_REQUIRED, () -> validatePackage(entries, null, Map.of()));
        assertThat(validatePackage(entries, "보호자", unrestrictedPolicies())).hasSize(3);
    }

    /** 받는 사람의 생년월일 형식도 신청자와 같은 기준으로 본다. */
    @Test
    void packageRecipientBirthMustBeValid() {
        expectError(ErrorCode.INVALID_REGISTRATION_BIRTH, () -> validatePackage(List.of(
                entry("xmen", "1991-13-01"), entry("avengers", "1992-01-01"), entry("fantastic4", "1993-01-01")), null,
                Map.of()));
    }

    /** 받는 사람은 패키지가 아니라 자기 하위 종목의 출생일 정책을 따른다. */
    @Test
    void packageRecipientFollowsChildCategoryBirthPolicy() {
        Map<String, EventCategoryRegistrationPolicy> policies = unrestrictedPolicies("xmen");
        EventCategoryRegistrationPolicy adultsOnly = mock(EventCategoryRegistrationPolicy.class);
        when(adultsOnly.getEventCategory()).thenReturn(components.get(1));
        when(adultsOnly.getAllowedBirthTo()).thenReturn(LocalDate.of(2000, 12, 31));
        policies.put("xmen", adultsOnly);

        expectError(ErrorCode.REGISTRATION_CATEGORY_BIRTH_NOT_ALLOWED, () -> validatePackage(List.of(
                entry("xmen", "2005-01-01"), entry("avengers", "1992-01-01"), entry("fantastic4", "1993-01-01")), null,
                policies));
        assertThat(validatePackage(List.of(
                entry("xmen", "1999-01-01"), entry("avengers", "2005-01-01"), entry("fantastic4", "1993-01-01")), null,
                policies)).hasSize(3);
    }

    /** 옵션 상품은 구매 줄로 살 수 없다. */
    @Test
    void optionCannotBePurchasedAlone() {
        expectError(ErrorCode.PRODUCT_NOT_PURCHASABLE_ALONE, () -> validateItem(category(ProductType.OPTION),
                List.of(), null, null, null, Map.of()));
    }

    /** 상향은 지금 단품을 뺀 하위 종목마다 받는 사람 한 명으로 항목을 만든다. */
    @Test
    void upgradeCreatesEntriesForOtherChildren() {
        List<RegistrationEntryDraft> entries = validateUpgrade(List.of(
                entry("fantastic4", "1993-01-01"), entry("xmen", "1991-01-01")), null, false,
                eventPolicy(), unrestrictedPolicies("avengers"));

        assertThat(entries).extracting(entry -> entry.eventCategory().getId())
                .containsExactly("fantastic4", "xmen");
    }

    /** 상향 입력에 지금 단품이 있거나, 종목이 빠지거나 겹치면 거절한다. */
    @Test
    void upgradeRejectsCurrentMissingOrDuplicatedChildren() {
        expectEntriesError(() -> validateUpgrade(List.of(
                entry("avengers", "1991-01-01"), entry("xmen", "1992-01-01")), null, false, null, Map.of()));
        expectEntriesError(() -> validateUpgrade(List.of(entry("xmen", "1992-01-01")), null, false, null, Map.of()));
        expectEntriesError(() -> validateUpgrade(List.of(
                entry("xmen", "1991-01-01"), entry("xmen", "1992-01-01")), null, false, null, Map.of()));
        expectEntriesError(() -> validateUpgrade(null, null, false, null, Map.of()));
    }

    /** 지금 단품이 패키지 구성에 없으면 설정 오류다. */
    @Test
    void upgradeRequiresCurrentSingleInPackage() {
        EventCategory other = EventCategory.builder().id("other").productType(ProductType.SINGLE)
                .parentCategoryId("another-package").build();

        expectError(ErrorCode.REGISTRATION_POLICY_CONFIGURATION_ERROR, () -> validator.validateUpgrade(
                event, category(ProductType.PACKAGE), other, components,
                List.of(entry("xmen", "1991-01-01"), entry("fantastic4", "1992-01-01")),
                null, false, null, Map.of(), TODAY));
    }

    /** 14세 미만 받는 사람은 신청 행에 저장된 보호자 값으로 판정한다. */
    @Test
    void upgradeRecipientUnderGuardianAgeUsesStoredGuardian() {
        List<RegistrationEntryRequest> entries = List.of(entry("xmen", "2015-01-01"), entry("fantastic4", "1993-01-01"));

        expectError(ErrorCode.GUARDIAN_NAME_REQUIRED,
                () -> validateUpgrade(entries, null, false, eventPolicy(), Map.of()));
        assertThat(validateUpgrade(entries, "보호자", true, eventPolicy(), unrestrictedPolicies("avengers"))).hasSize(2);
    }

    private List<RegistrationEntryDraft> validateUpgrade(
            List<RegistrationEntryRequest> entries,
            String guardianName,
            boolean guardianConsent,
            EventRegistrationPolicy eventPolicy,
            Map<String, EventCategoryRegistrationPolicy> componentPolicies) {
        return validator.validateUpgrade(event, category(ProductType.PACKAGE), components.get(0), components,
                entries, guardianName, guardianConsent, eventPolicy, componentPolicies, TODAY);
    }

    private List<RegistrationEntryDraft> validateItem(
            EventCategory product,
            List<EventCategory> children,
            List<RegistrationEntryRequest> entries,
            String guardianName,
            EventRegistrationPolicy eventPolicy,
            Map<String, EventCategoryRegistrationPolicy> entryPolicies) {
        return validator.validateItem(event, product, children, entries, guardianName,
                guardianName == null ? null : true, eventPolicy, entryPolicies, TODAY);
    }

    /** 받는 사람 검증까지 닿는 패키지 줄. 대회 정책은 이때만 만든다. */
    private List<RegistrationEntryDraft> validatePackage(
            List<RegistrationEntryRequest> entries,
            String guardianName,
            Map<String, EventCategoryRegistrationPolicy> componentPolicies) {
        return validateItem(category(ProductType.PACKAGE), components, entries, guardianName, eventPolicy(),
                componentPolicies);
    }

    private EventRegistrationPolicy eventPolicy() {
        EventRegistrationPolicy policy = mock(EventRegistrationPolicy.class);
        when(policy.getEvent()).thenReturn(event);
        lenient().when(policy.isGuardianConsentRequired()).thenReturn(true);
        when(policy.getGuardianRequiredBirthFrom()).thenReturn(LocalDate.of(2012, 11, 6));
        return policy;
    }

    /** 출생일 제한이 없는 하위 종목 정책. except 종목은 테스트가 따로 넣는다. */
    private Map<String, EventCategoryRegistrationPolicy> unrestrictedPolicies(String... except) {
        Map<String, EventCategoryRegistrationPolicy> policies = new HashMap<>();
        for (EventCategory component : components) {
            if (List.of(except).contains(component.getId())) {
                continue;
            }
            EventCategoryRegistrationPolicy policy = mock(EventCategoryRegistrationPolicy.class);
            when(policy.getEventCategory()).thenReturn(component);
            policies.put(component.getId(), policy);
        }
        return policies;
    }

    private static EventCategory category(ProductType productType) {
        return EventCategory.builder().id("category").productType(productType).build();
    }

    private static EventCategory component(String id) {
        return EventCategory.builder().id(id).productType(ProductType.SINGLE).parentCategoryId("category").build();
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
