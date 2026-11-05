package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import kr.co.teambrain.marvelrun.common.inheritance_enum.ProductType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationCreateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationEntryDraft;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationItemDraft;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationPolicyContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationEntry;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationItem;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.policy.EventCategoryRegistrationPolicy;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationItemRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.dto.RegistrationPolicyInput;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.loader.RegistrationPolicyLoader;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategoryCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategorySouvenirCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationEntryCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationItemCommandRepository;
import org.springframework.stereotype.Component;

import kr.co.teambrain.marvelrun.user.event.command.application.valid.dto.RegistrationPolicySelection;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.dto.RegistrationPolicyValidationResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Registration 생성 신청에 필요한 도메인 객체를 조회하고,
 * 신청 가능 여부를 검증한 뒤 RegistrationCreateContext를 생성한다.
 */
@Component
public class RegistrationApplyValidator extends AbstractRegistrationApplyValidator {

    /** 한 신청에 담을 수 있는 구매 줄 수 */
    private static final int MAX_ITEMS = 10;

    private final RegistrationEntryValidator registrationEntryValidator;

    private final RegistrationItemCommandRepository registrationItemCommandRepository;

    private final RegistrationEntryCommandRepository registrationEntryCommandRepository;

    /**
     * 개인 신청 검증에 필요한 기존 조회·정책 의존성을 연결한다.
     */
    public RegistrationApplyValidator(
            RegistrationCommandRepository registrationCommandRepository,
            EventCommandRepository eventCommandRepository,
            EventCategoryCommandRepository eventCategoryCommandRepository,
            EventCategorySouvenirCommandRepository eventCategorySouvenirCommandRepository,
            RegistrationPolicyLoader registrationPolicyLoader,
            RegistrationPolicyValidator registrationPolicyValidator,
            RegistrationEntryValidator registrationEntryValidator,
            RegistrationItemCommandRepository registrationItemCommandRepository,
            RegistrationEntryCommandRepository registrationEntryCommandRepository
    ) {
        super(
                registrationCommandRepository,
                eventCommandRepository,
                eventCategoryCommandRepository,
                eventCategorySouvenirCommandRepository,
                registrationPolicyLoader,
                registrationPolicyValidator
        );
        this.registrationEntryValidator = registrationEntryValidator;
        this.registrationItemCommandRepository = registrationItemCommandRepository;
        this.registrationEntryCommandRepository = registrationEntryCommandRepository;
    }


    /**
     * 개인 신청 생성 시 사용하는 최상위 검증 진입점.
     *
     * Service는 개별 검증 로직을 알지 않고,
     * 검증 완료된 Context만 전달받는다.
     * 공개 신청이므로 같은 신청자의 살아 있는 신청이 있으면 거절한다.
     */
    public RegistrationCreateContext validate(
            String eventId,
            RegistrationCreateRequest request,
            LocalDateTime now
    ) {
        return validate(eventId, request, now, false);
    }

    /**
     * 개인 신청 생성 검증.
     *
     * @param additionalOrder 로그인한 신청자의 추가 주문이면 true.
     *                        같은 신청자 주문 여러 건을 허용한 대회에서만 같은 신청자 거절을 건너뛴다.
     */
    public RegistrationCreateContext validate(
            String eventId,
            RegistrationCreateRequest request,
            LocalDateTime now,
            boolean additionalOrder
    ) {
        Event event =
                getEvent(eventId);

        validateEvent(
                event,
                now
        );

        validateSameApplicant(
                eventId,
                request,
                additionalOrder
        );

        if (request.hasItems()) {
            return validateItems(
                    event,
                    request,
                    now
            );
        }

        Set<String> souvenirIds =
                collectRequestedSouvenirIds(
                        request.selectedSouvenirList()
                );

        SelectionData selections =
                loadSelections(
                        event,
                        Map.of(
                                request.eventCategoryId(),
                                souvenirIds
                        )
                );

        RegistrationPolicyContext policies =
                loadPolicies(
                        event,
                        selections
                );

        RegistrationPolicyValidationResult validated =
                validateParticipantSelection(
                        event,
                        new RegistrationPolicySelection(
                                request.eventCategoryId(),
                                request.selectedSouvenirList(),
                                new RegistrationPolicyInput(
                                        request.birth(),
                                        request.guardianName(),
                                        request.guardianConsent()
                                )
                        ),
                        selections,
                        policies,
                        now.toLocalDate()
                );

        registrationPolicyValidator.validateAddress(
                policies.eventPolicy(),
                request.address()
        );

        EventCategory eventCategory =
                validated.eventCategory();

        // 상품은 구매 줄로만 산다. 최상위 종목은 참가 항목이 없는 일반 종목이다.
        registrationEntryValidator.validateWithoutItems(
                eventCategory,
                request.entries()
        );

        return new RegistrationCreateContext(
                event,
                eventCategory,
                validated.souvenirJsons(),
                List.of(),
                policies.eventPolicy().isDuplicateRegistrationAllowed()
        );
    }

    /**
     * 같은 신청자(이름·연락처·생년월일)의 살아 있는 신청이 있으면 거절한다.
     *
     * 같은 신청자 주문 여러 건을 허용한 대회는 공개 신청에 로그인 후 추가 주문을 안내하고,
     * 로그인한 추가 주문만 통과시킨다. 대회 정책은 같은 신청자가 있을 때만 읽는다.
     */
    private void validateSameApplicant(
            String eventId,
            RegistrationCreateRequest request,
            boolean additionalOrder
    ) {
        if (!isAlreadyRegisteredParticipant(
                eventId,
                request.name(),
                request.phNum(),
                request.birth()
        )) {
            return;
        }

        boolean duplicateAllowed =
                registrationPolicyLoader.loadEventPolicy(eventId)
                        .isDuplicateRegistrationAllowed();

        if (!duplicateAllowed) {
            throw new CustomException(
                    ErrorCode.REGISTRATION_ALREADY_EXISTS
            );
        }

        if (!additionalOrder) {
            throw new CustomException(
                    ErrorCode.REGISTRATION_ADDITIONAL_ORDER_LOGIN_REQUIRED
            );
        }
    }

    /**
     * 상품 구매 줄을 검증한다.
     *
     * 신청자는 대회 정책(생년월일 형식·보호자)만 보고, 종목 출생일 범위는 받는 사람마다 자기 종목으로 본다.
     * 상품은 연령 구분이 없어 기념품 연령별 사이즈 제한은 보지 않는다. 같은 상품을 여러 번 사도 종목·정책은 한 번만 읽는다.
     * 첫 줄의 상품·기념품이 신청 행의 대표값이 된다.
     */
    private RegistrationCreateContext validateItems(
            Event event,
            RegistrationCreateRequest request,
            LocalDateTime now
    ) {
        List<RegistrationItemRequest> items =
                request.items();

        if (items.size() > MAX_ITEMS) {
            throw new CustomException(
                    ErrorCode.REGISTRATION_ITEM_LIMIT_EXCEEDED,
                    " itemCount=" + items.size()
            );
        }

        Map<String, Set<String>> souvenirIdsByCategory =
                new LinkedHashMap<>();

        for (RegistrationItemRequest item : items) {
            souvenirIdsByCategory.computeIfAbsent(
                    item.eventCategoryId(),
                    ignored -> new HashSet<>()
            ).addAll(
                    collectRequestedSouvenirIds(
                            item.selectedSouvenirList()
                    )
            );
        }

        SelectionData selections =
                loadSelections(
                        event,
                        souvenirIdsByCategory
                );

        RegistrationPolicyContext policies =
                loadPolicies(
                        event,
                        selections
                );

        LocalDate applicationDate =
                now.toLocalDate();

        registrationPolicyValidator.validateParticipant(
                        event,
                        policies.eventPolicy(),
                        new RegistrationPolicyInput(
                                request.birth(),
                                request.guardianName(),
                                request.guardianConsent()
                        ),
                        applicationDate
                );

        registrationPolicyValidator.validateAddress(
                policies.eventPolicy(),
                request.address()
        );

        Map<String, List<EventCategory>> componentsByProduct =
                packageComponentsByProduct(
                        event,
                        selections.categories().values()
                );

        Map<String, EventCategoryRegistrationPolicy> entryPolicies =
                registrationPolicyLoader.loadCategoryPolicies(
                        event.getId(),
                        entryCategoryIds(
                                selections.categories().values(),
                                componentsByProduct
                        )
                );

        List<RegistrationItemDraft> drafts =
                new ArrayList<>(items.size());

        for (RegistrationItemRequest item : items) {
            EventCategory product =
                    selections.categories().get(
                            item.eventCategoryId()
                    );

            List<RegistrationEntryDraft> entries =
                    registrationEntryValidator.validateItem(
                            event,
                            product,
                            componentsByProduct.get(product.getId()),
                            item.entries(),
                            request.guardianName(),
                            request.guardianConsent(),
                            policies.eventPolicy(),
                            entryPolicies,
                            applicationDate
                    );

            // 상품은 연령 구분이 없어 기념품 연령별 사이즈 제한을 보지 않는다.
            List<SouvenirJson> souvenirs =
                    validateSouvenirs(
                            item.selectedSouvenirList(),
                            selections.mappingsByCategory().get(
                                    product.getId()
                            ),
                            null,
                            policies
                    );

            drafts.add(
                    new RegistrationItemDraft(
                            product,
                            souvenirs,
                            entries
                    )
            );
        }

        RegistrationItemDraft representative =
                drafts.getFirst();

        return new RegistrationCreateContext(
                event,
                representative.product(),
                representative.souvenirs(),
                List.copyOf(drafts),
                policies.eventPolicy().isDuplicateRegistrationAllowed()
        );
    }

    /** 상품마다 패키지 하위 종목을 읽는다. 같은 패키지를 여러 번 사도 한 번만 읽는다. */
    private Map<String, List<EventCategory>> packageComponentsByProduct(
            Event event,
            Collection<EventCategory> products
    ) {
        Map<String, List<EventCategory>> result =
                new HashMap<>();

        for (EventCategory product : products) {
            result.put(
                    product.getId(),
                    packageComponents(event, product)
            );
        }

        return result;
    }

    /** 받는 사람 종목은 단품 줄이면 그 상품, 패키지 줄이면 하위 종목이다. */
    private static Set<String> entryCategoryIds(
            Collection<EventCategory> products,
            Map<String, List<EventCategory>> componentsByProduct
    ) {
        Set<String> result =
                new HashSet<>();

        for (EventCategory product : products) {
            if (product.getProductType() == ProductType.SINGLE) {
                result.add(product.getId());
            }
            componentsByProduct.get(product.getId())
                    .forEach(component -> result.add(component.getId()));
        }

        return result;
    }

    /**
     * 패키지 상품이면 parent_category_id로 그 패키지를 가리키는 하위 종목을 돌려준다.
     * 단품 판매를 멈춰도(비활성) 패키지 구성은 바뀌지 않으므로 활성 여부는 보지 않는다.
     * 패키지가 아니면 조회하지 않고 빈 목록을 돌려준다.
     */
    private List<EventCategory> packageComponents(
            Event event,
            EventCategory eventCategory
    ) {
        if (eventCategory.getProductType() != ProductType.PACKAGE) {
            return List.of();
        }

        return eventCategoryCommandRepository
                .findByEvent_IdAndParentCategoryIdOrderByOrderAscIdAsc(
                        event.getId(),
                        eventCategory.getId()
                );
    }

    /**
     * 저장된 미결제 신청들의 자원 재확보 가능 여부를 검증한다.
     *
     * 현재 대회 상태와 신청 기간, 종목 및 기념품 매핑,
     * 참가자 출생일·보호자·사이즈 정책을 다시 확인한다.
     * 상품 신청은 신청 행의 대표 상품이 아니라 저장된 구매 줄과 참가 항목으로 확인한다.
     *
     * 기존 신청 자체를 검증하므로 신규 신청의 중복 참가자 검사는 수행하지 않는다.
     * 신청 내용과 계약금액은 변경하지 않는다.
     *
     * 호출자는 동일 트랜잭션에서 대회 잠금을 먼저 획득해야 한다.
     */
    public void validateReacquisition(
            Event event,
            List<Registration> registrations,
            LocalDateTime now
    ) {

        if (registrations.isEmpty()) {
            throw new CustomException(
                    ErrorCode.INVALID_RESERVATION_ARGUMENT,
                    " 재확보 대상 신청 목록이 비어 있습니다."
            );
        }

        validateEvent(event, now);

        List<String> productRegistrationIds =
                new ArrayList<>();

        for (Registration registration : registrations) {

            /*
             * 최초 결제 전의 유효한 신청만 재확보한다.
             * 삭제된 신청을 복구하거나 결제 완료 신청을 변경하지 않는다.
             */
            if (
                    !event.getId().equals(
                            registration.getEvent().getId()
                    )
                            || registration.isSoftDeleted()
                            || registration.getStatus()
                            != RegistrationStatus.PAYMENT_PENDING
            ) {
                throw new CustomException(
                        ErrorCode.RESERVATION_STATE_CONFLICT,
                        " 재확보 가능한 미결제 신청이 아닙니다."
                                + " registrationId=" + registration.getId()
                );
            }

            if (registration.isProductPurchase()) {
                productRegistrationIds.add(registration.getId());
            }
        }

        Map<String, List<RegistrationItem>> itemsByRegistration =
                loadItems(productRegistrationIds);

        Map<String, List<RegistrationEntry>> entriesByItem =
                loadEntriesByItem(productRegistrationIds);

        Map<String, Set<String>> souvenirIdsByCategory =
                new HashMap<>();

        Set<String> entryCategoryIds =
                new HashSet<>();

        for (Registration registration : registrations) {
            if (!itemsByRegistration.containsKey(registration.getId())) {
                souvenirIdsByCategory.computeIfAbsent(
                        registration.getEventCategory().getId(),
                        ignored -> new HashSet<>()
                ).addAll(
                        collectRequestedSouvenirIds(
                                registration.getSouvenirJson()
                        )
                );
                continue;
            }

            for (RegistrationItem item : itemsByRegistration.get(registration.getId())) {
                souvenirIdsByCategory.computeIfAbsent(
                        item.getEventCategory().getId(),
                        ignored -> new HashSet<>()
                ).addAll(
                        collectRequestedSouvenirIds(
                                item.getSouvenirJson()
                        )
                );

                for (RegistrationEntry entry : entriesOf(item, entriesByItem)) {
                    entryCategoryIds.add(entry.getEventCategory().getId());
                }
            }
        }

        /*
         * 기존 신청 Validator의 조회·소속·활성 검증을 재사용한다.
         * 같은 종목과 정책은 이번 호출에서 함께 조회한다.
         */
        SelectionData selections =
                loadSelections(
                        event,
                        souvenirIdsByCategory
                );

        RegistrationPolicyContext policies =
                loadPolicies(
                        event,
                        selections
                );

        Map<String, EventCategoryRegistrationPolicy> entryPolicies =
                registrationPolicyLoader.loadCategoryPolicies(
                        event.getId(),
                        entryCategoryIds
                );

        for (Registration registration : registrations) {

            Organization organization =
                    registration.getOrganization();

            /*
             * 기존 개인·단체 신청과 동일한 보호자 입력 기준을 사용한다.
             * organization은 개인 신청에서 존재하지 않는 선택적 관계이다.
             */
            RegistrationPolicyInput input =
                    organization == null
                            ? new RegistrationPolicyInput(
                            registration.getBirth(),
                            registration.getGuardianName(),
                            registration.isGuardianConsent()
                    )
                            : new RegistrationPolicyInput(
                            registration.getBirth(),
                            organization.getLeaderName(),
                            organization.isGuardianConsent()
                    );

            LocalDate birth =
                    registrationPolicyValidator.validateParticipant(
                            event,
                            policies.eventPolicy(),
                            input,
                            now.toLocalDate()
                    );

            List<RegistrationItem> items =
                    itemsByRegistration.get(registration.getId());

            if (items != null) {
                validateStoredItems(
                        event,
                        registration,
                        items,
                        entriesByItem,
                        selections,
                        policies,
                        entryPolicies,
                        now.toLocalDate()
                );
                continue;
            }

            EventCategory category =
                    selections.categories().get(
                            registration.getEventCategory().getId()
                    );

            registrationPolicyValidator.validateCategoryBirth(
                    category,
                    policies.categoryPolicies().get(category.getId()),
                    birth
            );

            /*
             * 최초 신청 때 저장한 선택값이 현재 정책에서도 유효한지 확인한다.
             * 이 경로에서는 종목이나 사이즈를 다른 값으로 변경하지 않는다.
             */
            validateStoredSouvenirs(
                    registration,
                    registration.getSouvenirJson(),
                    category,
                    birth,
                    selections,
                    policies
            );
        }
    }

    /**
     * 상품 신청의 저장된 구매 줄을 다시 검증한다.
     * 줄마다 기념품을, 참가 항목마다 보호자·자기 종목 출생일 범위를 본다.
     * 상품은 연령 구분이 없어 기념품 연령별 사이즈 제한은 보지 않는다(신청 때와 같은 규칙).
     */
    private void validateStoredItems(
            Event event,
            Registration registration,
            List<RegistrationItem> items,
            Map<String, List<RegistrationEntry>> entriesByItem,
            SelectionData selections,
            RegistrationPolicyContext policies,
            Map<String, EventCategoryRegistrationPolicy> entryPolicies,
            LocalDate applicationDate
    ) {
        for (RegistrationItem item : items) {
            EventCategory product =
                    selections.categories().get(
                            item.getEventCategory().getId()
                    );

            validateStoredSouvenirs(
                    registration,
                    item.getSouvenirJson() == null ? List.of() : item.getSouvenirJson(),
                    product,
                    null,
                    selections,
                    policies
            );

            List<RegistrationEntry> entries =
                    entriesOf(item, entriesByItem);

            if (entries.isEmpty()) {
                throw new CustomException(
                        ErrorCode.REGISTRATION_POLICY_CONFIGURATION_ERROR,
                        " 구매 줄에 참가 항목이 없습니다. itemId=" + item.getId()
                );
            }

            for (RegistrationEntry entry : entries) {
                LocalDate entryBirth =
                        registrationPolicyValidator.validateParticipant(
                                event,
                                policies.eventPolicy(),
                                new RegistrationPolicyInput(
                                        entry.getBirth(),
                                        registration.getGuardianName(),
                                        registration.isGuardianConsent()
                                ),
                                applicationDate
                        );

                registrationPolicyValidator.validateCategoryBirth(
                        entry.getEventCategory(),
                        entryPolicies.get(entry.getEventCategory().getId()),
                        entryBirth
                );
            }
        }
    }

    /**
     * 저장된 기념품 선택이 현재 정책에서도 유효한지 확인한다.
     *
     * 확보 계산에는 저장된 값을 그대로 사용하므로,
     * 검증 과정의 정규화 결과와 저장값이 다르면 진행하지 않는다.
     */
    private void validateStoredSouvenirs(
            Registration registration,
            List<SouvenirJson> stored,
            EventCategory category,
            LocalDate birth,
            SelectionData selections,
            RegistrationPolicyContext policies
    ) {
        List<SouvenirJson> validatedSouvenirs =
                validateSouvenirs(
                        stored,
                        selections.mappingsByCategory().get(
                                category.getId()
                        ),
                        birth,
                        policies
                );

        if (!validatedSouvenirs.equals(stored)) {
            throw new CustomException(
                    ErrorCode.INVALID_SOUVENIR_SIZE,
                    " 저장된 기념품 선택값이 현재 검증 결과와 일치하지 않습니다."
                            + " registrationId=" + registration.getId()
            );
        }
    }

    /** 상품 신청의 구매 줄을 신청 id별로 묶는다. 구매 줄이 없는 상품 신청은 설정 오류다. */
    private Map<String, List<RegistrationItem>> loadItems(
            List<String> productRegistrationIds
    ) {
        if (productRegistrationIds.isEmpty()) {
            return Map.of();
        }

        Map<String, List<RegistrationItem>> result =
                new LinkedHashMap<>();

        for (RegistrationItem item :
                registrationItemCommandRepository.findAllByRegistrationIds(productRegistrationIds)) {
            result.computeIfAbsent(
                    item.getRegistration().getId(),
                    ignored -> new ArrayList<>()
            ).add(item);
        }

        for (String registrationId : productRegistrationIds) {
            if (!result.containsKey(registrationId)) {
                throw new CustomException(
                        ErrorCode.REGISTRATION_POLICY_CONFIGURATION_ERROR,
                        " 상품 신청에 구매 줄이 없습니다. registrationId=" + registrationId
                );
            }
        }

        return result;
    }

    /** 상품 신청의 참가 항목을 구매 줄 id별로 묶는다. */
    private Map<String, List<RegistrationEntry>> loadEntriesByItem(
            List<String> productRegistrationIds
    ) {
        if (productRegistrationIds.isEmpty()) {
            return Map.of();
        }

        return registrationEntryCommandRepository
                .findByRegistration_IdIn(productRegistrationIds)
                .stream()
                .collect(Collectors.groupingBy(
                        entry -> entry.getRegistrationItem().getId()
                ));
    }

    private static List<RegistrationEntry> entriesOf(
            RegistrationItem item,
            Map<String, List<RegistrationEntry>> entriesByItem
    ) {
        return entriesByItem.getOrDefault(item.getId(), List.of());
    }
}