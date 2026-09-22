package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import kr.co.teambrain.marvelrun.common.inheritance_enum.EventStatus;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationPolicyContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategorySouvenir;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Souvenir;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.dto.RegistrationPolicySelection;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.dto.RegistrationPolicyValidationResult;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.loader.RegistrationPolicyLoader;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategoryCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategorySouvenirCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 개인·단체 신청의 생성과 수정에서 공통으로 사용하는
 * 도메인 조회 및 검증 보조 처리를 제공한다.
 *
 * 각 신청의 최상위 검증 흐름과 Context 생성은
 * 해당 신청을 담당하는 자식 Validator에서 구성한다.
 *
 * 이 클래스는 Spring Bean으로 등록하지 않는다.
 * 현재 시각을 직접 조회하거나 요청별 값을 필드에 저장하지 않는다.
 */
public abstract class AbstractRegistrationApplyValidator {

    private final RegistrationPolicyLoader registrationPolicyLoader;

    protected final RegistrationPolicyValidator registrationPolicyValidator;

    private final RegistrationCommandRepository
            registrationCommandRepository;

    private final EventCommandRepository
            eventCommandRepository;

    private final EventCategoryCommandRepository
            eventCategoryCommandRepository;

    private final EventCategorySouvenirCommandRepository
            eventCategorySouvenirCommandRepository;

    protected AbstractRegistrationApplyValidator(
            RegistrationCommandRepository registrationCommandRepository,
            EventCommandRepository eventCommandRepository,
            EventCategoryCommandRepository eventCategoryCommandRepository,
            EventCategorySouvenirCommandRepository eventCategorySouvenirCommandRepository,
            RegistrationPolicyLoader registrationPolicyLoader,
            RegistrationPolicyValidator registrationPolicyValidator
    ) {
        this.registrationCommandRepository =
                registrationCommandRepository;
        this.eventCommandRepository =
                eventCommandRepository;
        this.eventCategoryCommandRepository =
                eventCategoryCommandRepository;
        this.eventCategorySouvenirCommandRepository =
                eventCategorySouvenirCommandRepository;
        this.registrationPolicyLoader =
                registrationPolicyLoader;
        this.registrationPolicyValidator =
                registrationPolicyValidator;
    }

    /**
     * Event 조회.
     *
     * 단체 신청 등 다른 Registration 생성 Validator에서도
     * MVP 동안 상속하여 재사용한다.
     */
    protected Event getEvent(
            String eventId
    ) {
        return eventCommandRepository
                .findById(eventId)
                .orElseThrow(
                        () -> new CustomException(
                                ErrorCode.EVENT_NOT_FOUND
                        )
                );
    }

    /**
     * 신청 생성 시 Event 자체가 신청 가능한 상태인지 검증.
     *
     * Service가 전달한 기준시각으로 OPEN 상태와 신청 기간을 검증한다.
     */
    protected void validateEvent(
            Event event,
            LocalDateTime now
    ) {
        registrationPolicyValidator.validateNewApplication(
                event,
                now
        );
    }

    /**
     * EventCategory 조회.
     *
     * 존재 여부만 책임지고,
     * Event 소속 및 활성 여부는 validateCategory()에서 검증한다.
     */
    protected EventCategory getEventCategory(
            String eventCategoryId
    ) {
        return eventCategoryCommandRepository
                .findById(eventCategoryId)
                .orElseThrow(
                        () -> new CustomException(
                                ErrorCode.EVENT_CATEGORY_NOT_FOUND
                        )
                );
    }

    /**
     * 선택한 Category가
     *
     * 1. 현재 신청 Event 소속인지
     * 2. 현재 신청 가능한 Category인지
     *
     * 검증한다.
     */
    protected void validateCategory(
            Event event,
            EventCategory eventCategory
    ) {
        if (!event.getId().equals(
                eventCategory.getEvent().getId()
        )) {
            throw new CustomException(
                    ErrorCode.EVENT_CATEGORY_NOT_FOUND
            );
        }

        if (!Boolean.TRUE.equals(
                eventCategory.getIsActive()
        )) {
            throw new CustomException(
                    ErrorCode.EVENT_CATEGORY_NOT_ACTIVE
            );
        }
    }

    /**
     * 요청된 전체 Souvenir 선택을 검증하고,
     * Registration.souvenirJson에 저장 가능한 정규화된 값으로 반환한다.
     *
     * 사전에 조회 및 검증한 매핑을 재사용하며,
     * 정규화된 사이즈에 출생일별 추가 정책을 적용한다.
     *
     * 현재는 종목에 매핑된 모든 기념품을 요청에 포함해야 한다.
     * 단체 신청도 참가자별로 기념품 구성이 일치하는지 확인한다.
     */
    protected List<SouvenirJson> validateSouvenirs(
            List<SouvenirJson> souvenirRequests,
            Map<String, EventCategorySouvenir> mappingBySouvenir,
            LocalDate birth,
            RegistrationPolicyContext policies
    ) {
        Set<String> requestedIds =
                souvenirRequests.stream()
                        .map(SouvenirJson::souvenirId)
                        .collect(Collectors.toSet());

        Set<String> expectedIds =
                mappingBySouvenir.keySet();

        if (!expectedIds.equals(requestedIds)) {
            throw new CustomException(
                    ErrorCode.INVALID_EVENT_CATEGORY_SOUVENIR
            );
        }

        List<SouvenirJson> result =
                new ArrayList<>(
                        souvenirRequests.size()
                );

        for (SouvenirJson souvenirRequest : souvenirRequests) {
            EventCategorySouvenir mapping =
                    mappingBySouvenir.get(
                            souvenirRequest.souvenirId()
                    );

            if (mapping == null) {
                throw new CustomException(
                        ErrorCode.INVALID_EVENT_CATEGORY_SOUVENIR
                );
            }

            Souvenir souvenir =
                    mapping.getSouvenir();

            String selectedSize =
                    validateAndNormalizeSize(
                            souvenir,
                            souvenirRequest.selectedSize()
                    );

            registrationPolicyValidator.validateSouvenirSize(
                    mapping,
                    policies.souvenirPolicies().get(mapping.getId()),
                    birth,
                    selectedSize
            );

            result.add(
                    new SouvenirJson(
                            souvenir.getId(),
                            selectedSize
                    )
            );
        }

        return List.copyOf(result);
    }

    /**
     * 최소 하나의 기념품 선택이 필요하다.
     *
     * 실제 기념품이 없는 종목은
     * "기념품 없음" Souvenir Mapping으로 처리한다.
     */
    protected void validateSouvenirRequestRequired(
            List<SouvenirJson> souvenirRequests
    ) {
        if (souvenirRequests == null || souvenirRequests.isEmpty()) {
            throw new CustomException(
                    ErrorCode.INVALID_EVENT_CATEGORY_SOUVENIR
            );
        }
    }

    /**
     * 하나의 Registration에서 같은 Souvenir를
     * 중복 선택할 수 없도록 검증한다.
     */
    protected void validateDuplicateSouvenir(
            List<SouvenirJson> souvenirRequests
    ) {
        Set<String> souvenirIds =
                new HashSet<>();

        for (SouvenirJson souvenirRequest : souvenirRequests) {
            if (!souvenirIds.add(
                    souvenirRequest.souvenirId()
            )) {
                throw new CustomException(
                        ErrorCode.DUPLICATE_SOUVENIR_SELECTION
                );
            }
        }
    }

    /**
     * 개별 Souvenir가 현재 신청에서 사용 가능한지 검증한다.
     */
    protected void validateSouvenir(
            Event event,
            EventCategory eventCategory,
            Souvenir souvenir
    ) {
        if (souvenir == null) {
            throw new CustomException(
                    ErrorCode.INVALID_EVENT_CATEGORY_SOUVENIR
            );
        }

        if (!event.getId().equals(
                souvenir.getEvent().getId()
        )) {
            throw new CustomException(
                    ErrorCode.INVALID_EVENT_CATEGORY_SOUVENIR
            );
        }

        if (!Boolean.TRUE.equals(
                souvenir.getIsActive()
        )) {
            throw new CustomException(
                    ErrorCode.SOUVENIR_NOT_ACTIVE
            );
        }
    }

    /**
     * 선택한 사이즈가 Souvenir에서 제공하는 사이즈인지 검증하고
     * DB 저장용 값으로 정규화한다.
     */
    protected String validateAndNormalizeSize(
            Souvenir souvenir,
            String selectedSize
    ) {
        Set<String> allowedSizes =
                getAllowedSizes(souvenir);

        if (allowedSizes.size() == 1 && allowedSizes.contains("FREE")) {
            return "FREE";
        }

        if (selectedSize == null || selectedSize.isBlank()) {
            throw new CustomException(
                    ErrorCode.INVALID_SOUVENIR_SIZE
            );
        }

        String normalizedSelectedSize =
                selectedSize.trim();

        if (!allowedSizes.contains(normalizedSelectedSize)) {
            throw new CustomException(
                    ErrorCode.INVALID_SOUVENIR_SIZE
            );
        }

        return normalizedSelectedSize;
    }

    /**
     * Souvenir.sizes DB 문자열을
     * 검증용 Set으로 변환한다.
     */
    protected Set<String> getAllowedSizes(
            Souvenir souvenir
    ) {
        String sizes =
                souvenir.getSizes();

        if (sizes == null || sizes.isBlank()) {
            return Set.of("FREE");
        }

        return Arrays.stream(
                        sizes.split("\\|")
                )
                .map(String::trim)
                .filter(size -> !size.isBlank())
                .collect(Collectors.toSet());
    }

    /**
     * 동일 Event에 동일 참가자 정보로 이미 Registration이 존재하는지 검증한다.
     *
     * 개인 신청과 단체 신청 양쪽에서 재사용한다.
     *
     * MVP에서는 Application-level exists 검증까지만 수행한다.
     * 동시 요청에 대한 완전한 중복 방지는 MVP 이후 별도 처리한다.
     */
    protected void validateAlreadyRegisteredParticipant(
            String eventId,
            String name,
            String phNum,
            String birth
    ) {
        if (registrationCommandRepository
                .existsByEventIdAndUniqueInfo(
                        eventId,
                        name,
                        phNum,
                        birth
                )) {
            throw new CustomException(
                    ErrorCode.REGISTRATION_ALREADY_EXISTS
            );
        }
    }

    /**
     * 한 참가자의 기념품 요청을 확인하고 조회할 기념품 ID를 반환한다.
     *
     * 참가자 사이에서 같은 기념품을 선택하는 것은 허용하지만,
     * 한 참가자의 선택 목록 내부 중복은 허용하지 않는다.
     */
    protected Set<String> collectRequestedSouvenirIds(
            List<SouvenirJson> souvenirRequests
    ) {
        validateSouvenirRequestRequired(
                souvenirRequests
        );

        validateDuplicateSouvenir(
                souvenirRequests
        );

        return souvenirRequests.stream()
                .map(SouvenirJson::souvenirId)
                .collect(Collectors.toSet());
    }

    /**
     * 신청에 포함된 종목과 기념품 매핑을 조회한다.
     *
     * 같은 종목은 한 번 조회하고,
     * 해당 종목의 전체 기념품 매핑을 한 번에 조회한다.
     *
     * 단체 신청에서는 참가자별 선택을 종목별로 합친 뒤 호출한다.
     * 조회 대상은 Map의 종목 ID로 결정하며,
     * 요청 기념품 ID로 전체 매핑을 필터링하지 않는다.
     *
     * 반환 결과는 해당 신청 작업의 지역변수로만 사용한다.
     */
    protected SelectionData loadSelections(
            Event event,
            Map<String, Set<String>> requestedSouvenirIdsByCategory
    ) {
        Map<String, EventCategory> categories =
                new LinkedHashMap<>();

        Map<String, Map<String, EventCategorySouvenir>> mappingsByCategory =
                new LinkedHashMap<>();

        for (String categoryId : requestedSouvenirIdsByCategory.keySet()) {
            EventCategory eventCategory =
                    getEventCategory(categoryId);

            validateCategory(
                    event,
                    eventCategory
            );

            List<EventCategorySouvenir> mappings =
                    eventCategorySouvenirCommandRepository
                            .findAllMappingsByCategoryId(
                                    eventCategory.getId()
                            );

            Map<String, EventCategorySouvenir> mappingBySouvenir =
                    new LinkedHashMap<>();

            for (EventCategorySouvenir mapping : mappings) {
                if (!eventCategory.getId().equals(
                        mapping.getEventCategory().getId()
                )) {
                    throw new CustomException(
                            ErrorCode.INVALID_EVENT_CATEGORY_SOUVENIR
                    );
                }

                Souvenir souvenir =
                        mapping.getSouvenir();

                validateSouvenir(
                        event,
                        eventCategory,
                        souvenir
                );

                EventCategorySouvenir previous =
                        mappingBySouvenir.putIfAbsent(
                                souvenir.getId(),
                                mapping
                        );

                if (previous != null) {
                    throw new CustomException(
                            ErrorCode.REGISTRATION_POLICY_CONFIGURATION_ERROR
                    );
                }
            }

            categories.put(
                    eventCategory.getId(),
                    eventCategory
            );

            mappingsByCategory.put(
                    eventCategory.getId(),
                    Map.copyOf(mappingBySouvenir)
            );
        }

        return new SelectionData(
                Map.copyOf(categories),
                Map.copyOf(mappingsByCategory)
        );
    }

    /**
     * 조회 및 소속 검증이 끝난 종목과 기념품 매핑으로 정책을 조회한다.
     *
     * 개인 신청과 단체 신청 모두 작업당 한 번 호출한다.
     */
    protected RegistrationPolicyContext loadPolicies(
            Event event,
            SelectionData selections
    ) {
        Set<String> mappingIds =
                selections.mappingsByCategory()
                        .values()
                        .stream()
                        .flatMap(mappingBySouvenir ->
                                mappingBySouvenir.values().stream()
                        )
                        .map(EventCategorySouvenir::getId)
                        .collect(Collectors.toSet());

        return registrationPolicyLoader.load(
                event.getId(),
                selections.categories().keySet(),
                mappingIds
        );
    }

    /**
     * 한 신청 작업에서 조회한 종목과 기념품 매핑을 보관한다.
     *
     * categories: 종목 ID -> 종목
     * mappingsByCategory: 종목 ID -> 기념품 ID -> 종목·기념품 매핑
     */
    protected record SelectionData(
            Map<String, EventCategory> categories,
            Map<String, Map<String, EventCategorySouvenir>> mappingsByCategory
    ) {
    }

    /**
     * 사전에 조회한 종목·매핑·정책으로 참가자 한 명을 검증한다.
     *
     * 호출자는 이 메서드 전에 기념품 요청의 필수 여부와 중복,
     * 종목·기념품의 소속 및 활성 상태를 검증해야 한다.
     *
     * 참가자 생년월일·보호자 → 종목 출생일 조건 → 기념품 정책 순서를 유지한다.
     * 대회 검증과 참가자 중복검사는 각 최상위 Validator가 담당한다.
     */
    protected RegistrationPolicyValidationResult validateParticipantSelection(
            Event event,
            RegistrationPolicySelection input,
            SelectionData selections,
            RegistrationPolicyContext policies,
            LocalDate applicationDate
    ) {
        LocalDate birth =
                registrationPolicyValidator.validateParticipant(
                        event,
                        policies.eventPolicy(),
                        input.participant(),
                        applicationDate
                );

        EventCategory eventCategory =
                selections.categories().get(
                        input.eventCategoryId()
                );

        registrationPolicyValidator.validateCategoryBirth(
                eventCategory,
                policies.categoryPolicies().get(eventCategory.getId()),
                birth
        );

        List<SouvenirJson> souvenirJsons =
                validateSouvenirs(
                        input.selectedSouvenirList(),
                        selections.mappingsByCategory().get(
                                eventCategory.getId()
                        ),
                        birth,
                        policies
                );

        return new RegistrationPolicyValidationResult(
                eventCategory,
                souvenirJsons
        );
    }

    /**
     * 단체장은 대회 당일 기준 만 14세 이상이어야 한다.
     *
     * 대회 당일과 단체장의 14번째 생일을 비교하며,
     * 14번째 생일 당일부터 단체 신청을 허용한다.
     *
     * eventDate를 인자로 받아
     * 날짜 경계 테스트에서 기준일을 고정할 수 있도록 한다.
     */
    protected void validateOrganizationLeaderAge(
            LocalDate leaderBirth,
            LocalDate eventDate
    ) {
        if (leaderBirth == null) {
            throw new CustomException(
                    ErrorCode.ORGANIZATION_LEADER_BIRTH_REQUIRED
            );
        }

        LocalDate fourteenthBirthday =
                leaderBirth.plusYears(14);

        if (eventDate.isBefore(fourteenthBirthday)) {
            throw new CustomException(
                    ErrorCode.ORGANIZATION_LEADER_MUST_BE_ADULT
            );
        }
    }

    /**
     * 저장된 단체장의 문자열 생년월일을 검증하고 연령 조건을 확인한다.
     *
     * Organization.leaderBirth는 String이므로 날짜 검증 후 변환한다.
     * 누락은 단체장 생년월일 필수 오류로,
     * 형식 오류·존재하지 않는 날짜·미래 날짜는 기존 생년월일 오류로 처리한다.
     */
    protected void validateStoredOrganizationLeaderAge(
            String leaderBirth,
            LocalDate eventDate,
            LocalDate applicationDate
    ) {
        if (leaderBirth == null || leaderBirth.isBlank()) {
            throw new CustomException(
                    ErrorCode.ORGANIZATION_LEADER_BIRTH_REQUIRED
            );
        }

        LocalDate parsedBirth =
                registrationPolicyValidator.parseBirth(
                        leaderBirth,
                        applicationDate
                );

        validateOrganizationLeaderAge(
                parsedBirth,
                eventDate
        );
    }
}