package kr.co.teambrain.marvelrun.user.event.command.application.valid;

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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 상품 종류에 맞는 참가 항목을 판정한다.
 *
 * 상품 종류가 없는 종목(기존 대회)은 참가 항목을 만들지 않는다. 상품은 구매 줄(items)로만 산다.
 * 단품 줄은 그 종목의 받는 사람 한 명으로, 패키지 줄은 하위 종목마다 받는 사람 한 명으로 항목을 만든다.
 * 받는 사람의 생년월일·보호자 조건은 대회 정책으로, 출생일 범위는 자기 종목의 정책으로 본다.
 * 보호자 입력은 신청에 하나이고 모든 받는 사람이 같이 쓴다.
 *
 * 조회는 호출부가 한다. 패키지 하위 종목은 parent_category_id로 패키지를 가리키는 종목 목록으로 넘겨받는다.
 */
@Component
@RequiredArgsConstructor
public class RegistrationEntryValidator {

    private static final String ENTRIES_NOT_ALLOWED =
            "이 종목은 받는 사람을 따로 입력하지 않습니다.";

    private static final String PRODUCT_ITEMS_REQUIRED =
            "단품·패키지 상품은 상품 목록(items)으로 신청해주세요.";

    private static final String ITEM_PRODUCT_REQUIRED =
            "상품 목록에는 단품·패키지 상품만 담을 수 있습니다.";

    private static final String SINGLE_ENTRY_REQUIRED =
            "단품은 그 종목의 받는 사람을 한 명 입력해주세요.";

    private static final String PACKAGE_ENTRIES_REQUIRED =
            "패키지는 종목마다 받는 사람을 한 명씩 입력해주세요.";

    private static final String UPGRADE_ENTRIES_REQUIRED =
            "더할 종목마다 받는 사람을 한 명씩 입력해주세요.";

    private final RegistrationPolicyValidator registrationPolicyValidator;

    /**
     * 구매 줄 없이 최상위 종목으로 신청할 때 본다. 일반 종목만 받고 받는 사람 입력은 받지 않는다.
     */
    public void validateWithoutItems(
            EventCategory category,
            List<RegistrationEntryRequest> entries
    ) {
        if (category.getProductType() != null) {
            throw invalidEntries(PRODUCT_ITEMS_REQUIRED);
        }
        if (entries != null && !entries.isEmpty()) {
            throw invalidEntries(ENTRIES_NOT_ALLOWED);
        }
    }

    /**
     * 구매 줄 하나의 참가 항목을 돌려준다.
     *
     * @param packageComponents 패키지 하위 종목. 패키지가 아니면 빈 목록
     * @param entryPolicies     받는 사람 종목(단품 줄의 상품, 패키지 하위 종목)의 출생일 정책
     */
    public List<RegistrationEntryDraft> validateItem(
            Event event,
            EventCategory product,
            List<EventCategory> packageComponents,
            List<RegistrationEntryRequest> entries,
            String guardianName,
            Boolean guardianConsent,
            EventRegistrationPolicy eventPolicy,
            Map<String, EventCategoryRegistrationPolicy> entryPolicies,
            LocalDate applicationDate
    ) {
        ProductType productType = product.getProductType();
        if (productType == null) {
            throw invalidEntries(ITEM_PRODUCT_REQUIRED);
        }

        return switch (productType) {
            case OPTION -> throw new CustomException(
                    ErrorCode.PRODUCT_NOT_PURCHASABLE_ALONE,
                    " eventCategoryId=" + product.getId()
            );
            case SINGLE -> single(
                    event, product, entries, guardianName, guardianConsent,
                    eventPolicy, entryPolicies, applicationDate
            );
            case PACKAGE -> packageEntries(
                    event, product, packageComponents, entries, guardianName, guardianConsent,
                    eventPolicy, entryPolicies, applicationDate
            );
        };
    }

    private List<RegistrationEntryDraft> single(
            Event event,
            EventCategory product,
            List<RegistrationEntryRequest> entries,
            String guardianName,
            Boolean guardianConsent,
            EventRegistrationPolicy eventPolicy,
            Map<String, EventCategoryRegistrationPolicy> entryPolicies,
            LocalDate applicationDate
    ) {
        if (entries == null || entries.size() != 1
                || !product.getId().equals(entries.get(0).eventCategoryId())) {
            throw invalidEntries(SINGLE_ENTRY_REQUIRED);
        }
        return List.of(recipientDraft(
                event, eventPolicy, entryPolicies, product, entries.get(0),
                guardianName, guardianConsent, applicationDate));
    }

    private List<RegistrationEntryDraft> packageEntries(
            Event event,
            EventCategory category,
            List<EventCategory> packageComponents,
            List<RegistrationEntryRequest> entries,
            String guardianName,
            Boolean guardianConsent,
            EventRegistrationPolicy eventPolicy,
            Map<String, EventCategoryRegistrationPolicy> componentPolicies,
            LocalDate applicationDate
    ) {
        boolean hasEntries = entries != null && !entries.isEmpty();
        Map<String, EventCategory> components = new LinkedHashMap<>();
        for (EventCategory component : packageComponents) {
            if (component.getProductType() != ProductType.SINGLE
                    || !category.getId().equals(component.getParentCategoryId())) {
                throw configurationError(" 패키지 하위 종목은 그 패키지를 가리키는 단품이어야 합니다."
                        + " eventCategoryId=" + category.getId() + " componentId=" + component.getId());
            }
            components.put(component.getId(), component);
        }
        if (components.isEmpty()) {
            throw configurationError(" 패키지에 하위 종목이 없습니다. eventCategoryId=" + category.getId());
        }
        if (!hasEntries || entries.size() != components.size()) {
            throw invalidEntries(PACKAGE_ENTRIES_REQUIRED);
        }

        // 개수가 같으므로 겹친 종목이 있으면 집합이 작아져 하위 종목 집합과 달라진다.
        Set<String> requestedCategoryIds = entries.stream()
                .map(RegistrationEntryRequest::eventCategoryId)
                .collect(Collectors.toSet());
        if (!requestedCategoryIds.equals(components.keySet())) {
            throw invalidEntries(PACKAGE_ENTRIES_REQUIRED);
        }

        return entries.stream()
                .map(entry -> recipientDraft(
                        event, eventPolicy, componentPolicies, components.get(entry.eventCategoryId()), entry,
                        guardianName, guardianConsent, applicationDate))
                .toList();
    }

    /**
     * 단품 → 패키지 상향 때 더할 참가 항목을 판정한다.
     * 대상은 패키지 하위 종목 가운데 지금 단품을 뺀 나머지이고, 종목마다 받는 사람이 한 명씩 있어야 한다.
     * 받는 사람이 14세 미만이면 신청 행에 저장된 보호자 값으로 판정한다.
     */
    public List<RegistrationEntryDraft> validateUpgrade(
            Event event,
            EventCategory packageCategory,
            EventCategory currentSingle,
            List<EventCategory> packageComponents,
            List<RegistrationEntryRequest> entries,
            String guardianName,
            boolean guardianConsent,
            EventRegistrationPolicy eventPolicy,
            Map<String, EventCategoryRegistrationPolicy> componentPolicies,
            LocalDate applicationDate
    ) {
        Map<String, EventCategory> components = new LinkedHashMap<>();
        for (EventCategory component : packageComponents) {
            if (component.getProductType() != ProductType.SINGLE
                    || !packageCategory.getId().equals(component.getParentCategoryId())) {
                throw configurationError(" 패키지 하위 종목은 그 패키지를 가리키는 단품이어야 합니다."
                        + " eventCategoryId=" + packageCategory.getId() + " componentId=" + component.getId());
            }
            if (!component.getId().equals(currentSingle.getId())) {
                components.put(component.getId(), component);
            }
        }
        if (components.isEmpty() || components.size() + 1 != packageComponents.size()) {
            throw configurationError(" 상향할 패키지 구성에 지금 단품이 없거나 더할 종목이 없습니다."
                    + " eventCategoryId=" + packageCategory.getId() + " currentId=" + currentSingle.getId());
        }
        if (entries == null || entries.size() != components.size()) {
            throw invalidEntries(UPGRADE_ENTRIES_REQUIRED);
        }
        Set<String> requestedCategoryIds = entries.stream()
                .map(RegistrationEntryRequest::eventCategoryId)
                .collect(Collectors.toSet());
        if (!requestedCategoryIds.equals(components.keySet())) {
            throw invalidEntries(UPGRADE_ENTRIES_REQUIRED);
        }

        return entries.stream()
                .map(entry -> recipientDraft(
                        event, eventPolicy, componentPolicies, components.get(entry.eventCategoryId()), entry,
                        guardianName, guardianConsent, applicationDate))
                .toList();
    }

    /** 받는 사람 한 명을 대회 보호자 정책과 자기 하위 종목의 출생일 정책으로 판정한다. */
    private RegistrationEntryDraft recipientDraft(
            Event event,
            EventRegistrationPolicy eventPolicy,
            Map<String, EventCategoryRegistrationPolicy> componentPolicies,
            EventCategory component,
            RegistrationEntryRequest entry,
            String guardianName,
            Boolean guardianConsent,
            LocalDate applicationDate
    ) {
        LocalDate birth = registrationPolicyValidator.validateParticipant(
                event,
                eventPolicy,
                new RegistrationPolicyInput(
                        entry.birth(),
                        guardianName,
                        guardianConsent
                ),
                applicationDate
        );
        registrationPolicyValidator.validateCategoryBirth(
                component,
                componentPolicies.get(component.getId()),
                birth
        );
        return new RegistrationEntryDraft(
                component,
                entry.name(),
                entry.phNum(),
                entry.birth(),
                entry.gender()
        );
    }

    private static CustomException invalidEntries(String responseMessage) {
        return CustomException.withResponseMessage(ErrorCode.INVALID_REGISTRATION_ENTRIES, responseMessage);
    }

    private static CustomException configurationError(String diagnostic) {
        return new CustomException(ErrorCode.REGISTRATION_POLICY_CONFIGURATION_ERROR, diagnostic);
    }
}
