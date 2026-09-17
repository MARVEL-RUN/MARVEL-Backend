package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import kr.co.teambrain.marvelrun.common.inheritance_enum.EventStatus;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationCreateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategorySouvenir;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Souvenir;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategoryCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategorySouvenirCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Registration 생성 신청에 필요한 도메인 객체를 조회하고,
 * 신청 가능 여부를 검증한 뒤 RegistrationCreateContext를 생성한다.
 */
@Component
@RequiredArgsConstructor
public class RegistrationApplyValidator {

    private final RegistrationCommandRepository
            registrationCommandRepository;

    private final EventCommandRepository
            eventCommandRepository;

    private final EventCategoryCommandRepository
            eventCategoryCommandRepository;

    private final EventCategorySouvenirCommandRepository
            eventCategorySouvenirCommandRepository;


    /**
     * 개인 신청 생성 시 사용하는 최상위 검증 진입점.
     *
     * Service는 개별 검증 로직을 알지 않고,
     * 검증 완료된 Context만 전달받는다.
     */
    public RegistrationCreateContext validate(
            String eventId,
            RegistrationCreateRequest request
    ) {

        Event event =
                getEvent(
                        eventId
                );


        validateEvent(
                event
        );


        validateAlreadyRegisteredParticipant(
                eventId,
                request.name(),
                request.phNum(),
                request.birth()
        );


        EventCategory eventCategory =
                getEventCategory(
                        request.eventCategoryId()
                );


        validateCategory(
                event,
                eventCategory
        );


        List<SouvenirJson> souvenirJsons =
                validateSouvenirs(
                        event,
                        eventCategory,
                        request.selectedSouvenirList()
                );


        return new RegistrationCreateContext(
                event,
                eventCategory,
                souvenirJsons
        );
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
                .findById(
                        eventId
                )
                .orElseThrow(
                        () -> new CustomException(
                                ErrorCode.EVENT_NOT_FOUND
                        )
                );
    }


    /**
     * 신청 생성 시 Event 자체가 신청 가능한 상태인지 검증.
     */
    protected void validateEvent(
            Event event
    ) {

        if (
                event.getEventStatus()
                        != EventStatus.OPEN
        ) {

            throw new CustomException(
                    ErrorCode.EVENT_NOT_OPEN
            );
        }
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
                .findById(
                        eventCategoryId
                )
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

        if (
                !event.getId()
                        .equals(
                                eventCategory
                                        .getEvent()
                                        .getId()
                        )
        ) {

            throw new CustomException(
                    ErrorCode.EVENT_CATEGORY_NOT_FOUND
            );
        }


        if (
                !Boolean.TRUE.equals(
                        eventCategory.getIsActive()
                )
        ) {

            throw new CustomException(
                    ErrorCode.EVENT_CATEGORY_NOT_ACTIVE
            );
        }
    }


    /**
     * 요청된 전체 Souvenir 선택을 검증하고,
     * Registration.souvenirJson에 저장 가능한 정규화된 값으로 반환한다.
     */
    protected List<SouvenirJson> validateSouvenirs(
            Event event,
            EventCategory eventCategory,
            List<SouvenirJson> souvenirRequests
    ) {

        validateSouvenirRequestRequired(
                souvenirRequests
        );


        validateDuplicateSouvenir(
                souvenirRequests
        );


        Set<String> souvenirIds =
                souvenirRequests.stream()
                        .map(
                                SouvenirJson::souvenirId
                        )
                        .collect(
                                Collectors.toSet()
                        );


        List<EventCategorySouvenir> mappings =
                eventCategorySouvenirCommandRepository
                        .findSelectedMappings(
                                eventCategory.getId(),
                                souvenirIds
                        );


        Map<String, Souvenir> souvenirMap =
                mappings.stream()
                        .map(
                                EventCategorySouvenir::getSouvenir
                        )
                        .collect(
                                Collectors.toMap(
                                        Souvenir::getId,
                                        Function.identity()
                                )
                        );


        List<SouvenirJson> result =
                new ArrayList<>(
                        souvenirRequests.size()
                );


        for (
                SouvenirJson souvenirRequest
                : souvenirRequests
        ) {

            Souvenir souvenir =
                    souvenirMap.get(
                            souvenirRequest.souvenirId()
                    );


            validateSouvenir(
                    event,
                    eventCategory,
                    souvenir
            );


            String selectedSize =
                    validateAndNormalizeSize(
                            souvenir,
                            souvenirRequest.selectedSize()
                    );


            result.add(
                    new SouvenirJson(
                            souvenir.getId(),
                            selectedSize
                    )
            );
        }


        return List.copyOf(
                result
        );
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

        if (
                souvenirRequests == null
                        || souvenirRequests.isEmpty()
        ) {

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


        for (
                SouvenirJson souvenirRequest
                : souvenirRequests
        ) {

            if (
                    !souvenirIds.add(
                            souvenirRequest.souvenirId()
                    )
            ) {

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


        if (
                !event.getId()
                        .equals(
                                souvenir
                                        .getEvent()
                                        .getId()
                        )
        ) {

            throw new CustomException(
                    ErrorCode.INVALID_EVENT_CATEGORY_SOUVENIR
            );
        }


        if (
                !Boolean.TRUE.equals(
                        souvenir.getIsActive()
                )
        ) {

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
                getAllowedSizes(
                        souvenir
                );


        if (
                allowedSizes.size() == 1
                        && allowedSizes.contains(
                        "FREE"
                )
        ) {

            return "FREE";
        }


        if (
                selectedSize == null
                        || selectedSize.isBlank()
        ) {

            throw new CustomException(
                    ErrorCode.INVALID_SOUVENIR_SIZE
            );
        }


        String normalizedSelectedSize =
                selectedSize.trim();


        if (
                !allowedSizes.contains(
                        normalizedSelectedSize
                )
        ) {

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


        if (
                sizes == null
                        || sizes.isBlank()
        ) {

            return Set.of(
                    "FREE"
            );
        }


        return Arrays.stream(
                        sizes.split("\\|")
                )
                .map(
                        String::trim
                )
                .filter(
                        size -> !size.isBlank()
                )
                .collect(
                        Collectors.toSet()
                );
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

        if (
                registrationCommandRepository
                        .existsByEventIdAndUniqueInfo(
                                eventId,
                                name,
                                phNum,
                                birth
                        )
        ) {

            throw new CustomException(
                    ErrorCode.REGISTRATION_ALREADY_EXISTS
            );
        }
    }
}