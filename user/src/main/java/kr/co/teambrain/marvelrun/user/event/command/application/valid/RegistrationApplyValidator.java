package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationCreateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationPolicyContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.dto.RegistrationPolicyInput;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.loader.RegistrationPolicyLoader;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategoryCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategorySouvenirCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import org.springframework.stereotype.Component;

import kr.co.teambrain.marvelrun.user.event.command.application.valid.dto.RegistrationPolicySelection;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.dto.RegistrationPolicyValidationResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Registration 생성 신청에 필요한 도메인 객체를 조회하고,
 * 신청 가능 여부를 검증한 뒤 RegistrationCreateContext를 생성한다.
 */
@Component
public class RegistrationApplyValidator extends AbstractRegistrationApplyValidator {

    /**
     * 개인 신청 검증에 필요한 기존 조회·정책 의존성을 연결한다.
     */
    public RegistrationApplyValidator(
            RegistrationCommandRepository registrationCommandRepository,
            EventCommandRepository eventCommandRepository,
            EventCategoryCommandRepository eventCategoryCommandRepository,
            EventCategorySouvenirCommandRepository eventCategorySouvenirCommandRepository,
            RegistrationPolicyLoader registrationPolicyLoader,
            RegistrationPolicyValidator registrationPolicyValidator
    ) {
        super(
                registrationCommandRepository,
                eventCommandRepository,
                eventCategoryCommandRepository,
                eventCategorySouvenirCommandRepository,
                registrationPolicyLoader,
                registrationPolicyValidator
        );
    }


    /**
     * 개인 신청 생성 시 사용하는 최상위 검증 진입점.
     *
     * Service는 개별 검증 로직을 알지 않고,
     * 검증 완료된 Context만 전달받는다.
     */
    public RegistrationCreateContext validate(
            String eventId,
            RegistrationCreateRequest request,
            LocalDateTime now
    ) {
        Event event =
                getEvent(eventId);

        validateEvent(
                event,
                now
        );

        validateAlreadyRegisteredParticipant(
                eventId,
                request.name(),
                request.phNum(),
                request.birth()
        );

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

        return new RegistrationCreateContext(
                event,
                validated.eventCategory(),
                validated.souvenirJsons()
        );
    }

    /**
     * 저장된 미결제 신청들의 자원 재확보 가능 여부를 검증한다.
     *
     * 현재 대회 상태와 신청 기간, 종목 및 기념품 매핑,
     * 참가자 출생일·보호자·사이즈 정책을 다시 확인한다.
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

        Map<String, Set<String>> souvenirIdsByCategory =
                new HashMap<>();

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

            Set<String> souvenirIds =
                    collectRequestedSouvenirIds(
                            registration.getSouvenirJson()
                    );

            souvenirIdsByCategory.computeIfAbsent(
                    registration.getEventCategory().getId(),
                    ignored -> new HashSet<>()
            ).addAll(souvenirIds);
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
            List<SouvenirJson> validatedSouvenirs =
                    validateSouvenirs(
                            registration.getSouvenirJson(),
                            selections.mappingsByCategory().get(
                                    category.getId()
                            ),
                            birth,
                            policies
                    );

            /*
             * 확보 계산에는 저장된 값을 그대로 사용하므로,
             * 검증 과정의 정규화 결과와 저장값이 다르면 진행하지 않는다.
             */
            if (!validatedSouvenirs.equals(
                    registration.getSouvenirJson()
            )) {
                throw new CustomException(
                        ErrorCode.INVALID_SOUVENIR_SIZE,
                        " 저장된 기념품 선택값이 현재 검증 결과와 일치하지 않습니다."
                                + " registrationId=" + registration.getId()
                );
            }
        }
    }
}