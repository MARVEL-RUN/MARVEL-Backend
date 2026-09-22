package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import jakarta.validation.Validator;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.context.OrgRegistrationModificationAccessContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationModificationParticipantRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/** 단체 개인정보 정정의 입력·기간·상태·중복만 확인하며 변경되지 않은 참가 정책은 조회하지 않는다. */
@Component
@RequiredArgsConstructor
public class OrgRegistrationPersonalInformationValidator {
    private final Validator inputValidator;
    private final RegistrationInformationPolicyValidator policyValidator;
    private final RegistrationUniqueInfoValidator uniqueInfoValidator;

    /** 내부 Command 호출에도 기존 DTO 제약을 적용한다. */
    public void validateInput(OrgRegistrationModificationRequest request) {
        if (request == null || !inputValidator.validate(request).isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_ARGUMENT);
        }
    }

    /** 모든 구성원을 변경하기 전에 최종목록 내부와 다른 활성 신청의 중복을 검사한다. */
    public void validate(OrgRegistrationModificationAccessContext access) {
        policyValidator.validatePeriod(access.event(), access.now());
        for (Registration registration : access.currentRegistrations()) {
            policyValidator.validateStatus(registration);
        }

        LocalDate leaderBirth = access.request().leaderBirth();
        LocalDate eventDate = access.event().getStartDate().toLocalDate();
        if (eventDate.isBefore(leaderBirth.plusYears(19))) {
            throw new CustomException(ErrorCode.ORGANIZATION_LEADER_MUST_BE_ADULT);
        }

        Set<UniqueInfo> finalIdentities = new HashSet<>();
        for (OrgRegistrationModificationParticipantRequest request : access.request().registrations()) {
            if (!finalIdentities.add(new UniqueInfo(request.name(), request.phNum(), request.birth()))) {
                throw new CustomException(ErrorCode.REGISTRATION_ALREADY_EXISTS);
            }
            // 기존 전체 수정과 같이 타 구성원이 현재 보유한 정보로의 맞교환도 허용하지 않는다.
            uniqueInfoValidator.validateOtherActive(access.event().getId(), request.registrationId(),
                    request.name(), request.phNum(), request.birth());
        }
    }

    /** 최종목록 내부에서 동일한 참가자 정보가 중복 제출되는지 확인하기 위한 값이다. */
    private record UniqueInfo(String name, String phNum, String birth) { }
}
