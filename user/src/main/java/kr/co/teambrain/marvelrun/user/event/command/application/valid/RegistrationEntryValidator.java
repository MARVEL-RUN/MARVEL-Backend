package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import kr.co.teambrain.marvelrun.common.inheritance_enum.ProductType;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationEntryDraft;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.policy.EventCategoryRegistrationPolicy;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.policy.EventRegistrationPolicy;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationCreateRequest;
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
 * 상품 종류가 없는 종목(기존 대회)은 참가 항목을 만들지 않는다.
 * 단품은 그 종목과 신청자 값으로 항목 하나를, 패키지는 하위 종목마다 받는 사람 값으로 항목을 만든다.
 * 받는 사람의 생년월일·보호자 조건은 대회 정책으로, 출생일 범위는 자기 하위 종목의 정책으로 본다.
 *
 * 조회는 호출부가 한다. 패키지 하위 종목은 parent_category_id로 패키지를 가리키는 종목 목록으로 넘겨받는다.
 */
@Component
@RequiredArgsConstructor
public class RegistrationEntryValidator {

    private static final String SINGLE_ENTRIES_NOT_ALLOWED =
            "이 상품은 받는 사람을 따로 입력하지 않습니다.";

    private static final String PACKAGE_ENTRIES_REQUIRED =
            "패키지는 종목마다 받는 사람을 한 명씩 입력해주세요.";

    private final RegistrationPolicyValidator registrationPolicyValidator;

    /**
     * 저장할 참가 항목을 돌려준다. 참가 항목이 없는 상품이면 빈 목록이다.
     *
     * 단품은 신청자 값을, 패키지는 요청의 받는 사람 값과 신청의 보호자 입력을 쓴다.
     *
     * @param packageComponents  패키지 하위 종목. 패키지가 아니면 빈 목록
     */
    public List<RegistrationEntryDraft> validate(
            Event event,
            EventCategory category,
            List<EventCategory> packageComponents,
            RegistrationCreateRequest request,
            EventRegistrationPolicy eventPolicy,
            Map<String, EventCategoryRegistrationPolicy> componentPolicies,
            LocalDate applicationDate
    ) {
        List<RegistrationEntryRequest> entries = request.entries();
        boolean hasEntries = entries != null && !entries.isEmpty();
        ProductType productType = category.getProductType();

        if (productType == null) {
            if (hasEntries) {
                throw invalidEntries(SINGLE_ENTRIES_NOT_ALLOWED);
            }
            return List.of();
        }

        return switch (productType) {
            case OPTION -> throw new CustomException(
                    ErrorCode.PRODUCT_NOT_PURCHASABLE_ALONE,
                    " eventCategoryId=" + category.getId()
            );
            case SINGLE -> single(category, request, hasEntries);
            case PACKAGE -> packageEntries(
                    event, category, packageComponents, request, hasEntries,
                    eventPolicy, componentPolicies, applicationDate
            );
        };
    }

    private List<RegistrationEntryDraft> single(
            EventCategory category,
            RegistrationCreateRequest request,
            boolean hasEntries
    ) {
        if (hasEntries) {
            throw invalidEntries(SINGLE_ENTRIES_NOT_ALLOWED);
        }
        return List.of(new RegistrationEntryDraft(
                category,
                request.name(),
                request.phNum(),
                request.birth(),
                request.gender()
        ));
    }

    private List<RegistrationEntryDraft> packageEntries(
            Event event,
            EventCategory category,
            List<EventCategory> packageComponents,
            RegistrationCreateRequest request,
            boolean hasEntries,
            EventRegistrationPolicy eventPolicy,
            Map<String, EventCategoryRegistrationPolicy> componentPolicies,
            LocalDate applicationDate
    ) {
        List<RegistrationEntryRequest> entries = request.entries();
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
                .map(entry -> {
                    LocalDate birth = registrationPolicyValidator.validateParticipant(
                            event,
                            eventPolicy,
                            new RegistrationPolicyInput(
                                    entry.birth(),
                                    request.guardianName(),
                                    request.guardianConsent()
                            ),
                            applicationDate
                    );
                    EventCategory component = components.get(entry.eventCategoryId());
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
                })
                .toList();
    }

    private static CustomException invalidEntries(String responseMessage) {
        return CustomException.withResponseMessage(ErrorCode.INVALID_REGISTRATION_ENTRIES, responseMessage);
    }

    private static CustomException configurationError(String diagnostic) {
        return new CustomException(ErrorCode.REGISTRATION_POLICY_CONFIGURATION_ERROR, diagnostic);
    }
}
