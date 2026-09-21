package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import jakarta.validation.Validator;
import java.util.Objects;

import kr.co.teambrain.marvelrun.user.event.command.application.domain.policy.EventRegistrationPolicy;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.dto.RegistrationPolicyInput;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventRegistrationPolicyRepository;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationModificationAccessContext;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationModificationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 개인정보 정정에 필요한 입력·기간·신청 상태만 검증한다. 종목·기념품·연령 정책은 조회하지 않는다. */
@Component
@RequiredArgsConstructor
public class RegistrationPersonalInformationValidator {
    private final Validator inputValidator;
    private final RegistrationInformationPolicyValidator policyValidator;
    private final RegistrationUniqueInfoValidator uniqueInfoValidator;
    private final EventRegistrationPolicyRepository eventRegistrationPolicyRepository;
    private final RegistrationPolicyValidator registrationPolicyValidator;

    /** Controller 외 호출에서도 기존 DTO 입력 제약을 적용하며 인증값을 오류에 노출하지 않는다. */
    public void validateInput(RegistrationModificationRequest request) {
        if (request == null || !inputValidator.validate(request).isEmpty()
                || (request.addressDetail() != null && request.addressDetail().length() > 255)) {
            throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_ARGUMENT);
        }
    }

    /** 기존 OPEN·접수 기간과 허용 신청 상태 및 활성 중복을 검사한다.
     * 개인정보 정정의 입력·기간·상태·중복을 검증한다.
     * 보호자 이름 변경 시에만 기존 참가자 정책을 추가 검증한다.
     * 종목·기념품·Capacity·결제 정보는 조회하지 않는다.
     */
    public void validate(RegistrationModificationAccessContext access) {
        policyValidator.validatePeriod(access.event(), access.now());
        policyValidator.validateStatus(access.registration());
        RegistrationModificationRequest request = access.request();
        uniqueInfoValidator.validateOtherActive(access.event().getId(), access.registration().getId(),
                request.name(), request.phNum(), request.birth());

        String guardianName = request.guardianName();
        if (guardianName != null) {
            guardianName = guardianName.strip();

            if (guardianName.isEmpty()) {
                guardianName = null;
            }
        }

        /**
         * 보호자 이름 변경에 필요한 기존 검증만 수행한다.
         * 종목·기념품·Capacity·결제 정보는 조회하지 않는다.
         */
        if (!Objects.equals(
                access.registration().getGuardianName(),
                guardianName
        )) {
            EventRegistrationPolicy guardianPolicy =
                    eventRegistrationPolicyRepository
                            .findByEventId(access.event().getId())
                            .orElseThrow(() -> new CustomException(
                                    ErrorCode.REGISTRATION_POLICY_CONFIGURATION_ERROR
                            ));

            registrationPolicyValidator.validateParticipant(
                    access.event(),
                    guardianPolicy,
                    new RegistrationPolicyInput(
                            request.birth(),
                            guardianName,
                            request.guardianConsent()
                    ),
                    access.now().toLocalDate()
            );
        }
    }
}
