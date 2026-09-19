package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationCreateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationModificationAccessContext;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationModificationCandidateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RegistrationModificationCandidateValidator {

    private final RegistrationCommandRepository
            registrationCommandRepository;

    private final RegistrationApplyValidator
            registrationApplyValidator;


    /**
     * 개인 수정 요청으로 변경 후 후보 상태를 구성하고
     * 신규 중복신청 검사를 제외한 01 정책 전체를 다시 수행한다.
     *
     * 검증 완료 전에는 현재 Registration Entity를 변경하지 않는다.
     */
    public RegistrationModificationCandidateContext validate(
            RegistrationModificationAccessContext accessContext
    ) {

        Registration currentRegistration =
                accessContext.registration();

        RegistrationModificationRequest request =
                accessContext.request();


        validateUniqueInfo(
                accessContext,
                currentRegistration,
                request
        );


        RegistrationCreateRequest candidateRequest =
                toPolicyRequest(
                        currentRegistration,
                        request
                );


        RegistrationCreateContext validated =
                registrationApplyValidator
                        .validateCandidate(
                                accessContext.event(),
                                candidateRequest,
                                accessContext.now()
                        );


        return new RegistrationModificationCandidateContext(
                accessContext.event(),
                currentRegistration,
                validated.eventCategory(),
                validated.souvenirJsons(),
                request,
                accessContext.now()
        );
    }


    /**
     * 수정 후 참가자 식별정보가
     * 다른 활성 Registration과 충돌하는지 확인한다.
     *
     * 현재 수정 중인 Registration 자체는 비교 대상에서 제외한다.
     */
    private void validateUniqueInfo(
            RegistrationModificationAccessContext accessContext,
            Registration currentRegistration,
            RegistrationModificationRequest request
    ) {

        if (
                registrationCommandRepository
                        .existsOtherActiveByEventIdAndUniqueInfo(
                                accessContext.event().getId(),
                                currentRegistration.getId(),
                                request.name(),
                                request.phNum(),
                                request.birth()
                        )
        ) {

            throw new CustomException(
                    ErrorCode.REGISTRATION_ALREADY_EXISTS
            );
        }
    }


    /**
     * 기존 01 개인 정책검증을 그대로 재사용하기 위해
     * 수정 후보값을 생성용 정책 입력 형태로 변환한다.
     *
     * password는 정책 판단대상이 아니므로
     * 현재 Registration 값을 그대로 사용한다.
     */
    private RegistrationCreateRequest toPolicyRequest(
            Registration currentRegistration,
            RegistrationModificationRequest request
    ) {

        return new RegistrationCreateRequest(
                request.eventCategoryId(),
                request.selectedSouvenirList(),
                currentRegistration.getPassword(),
                request.name(),
                request.phNum(),
                request.birth(),
                request.gender(),
                request.address(),
                request.addressDetail(),
                request.guardianName(),
                request.guardianConsent()
        );
    }
}