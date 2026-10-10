package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import java.time.LocalDateTime;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyModels;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 개인·단체 개인정보 정정에 공통인 기간과 신청 상태만 검사한다. 추가 정책 조회는 하지 않는다. */
@Component
@RequiredArgsConstructor
public class RegistrationInformationPolicyValidator {


    /** 이미 조회한 대회의 OPEN 상태와 접수 기간만 확인한다. */
    public void validateRegistrationModificationPeriod(Event event, LocalDateTime now) {
        RegistrationActionPolicyService.validateGlobalRegistrationActionPolicy(event, RegistrationActionPolicyModels.Action.MODIFY, now);
    }

    /** 금융 상태를 바꾸지 않고 정정할 수 있는 기존 활성 신청인지 확인한다. */
    public void validateStatus(Registration registration) {
        if (registration.isSoftDeleted() || registration.getStatus() == null) {
            throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET);
        }
        switch (registration.getStatus()) {
            case PAYMENT_PENDING, CONFIRMED, ADDITIONAL_PAYMENT_REQUIRED, PARTIAL_REFUND_REQUIRED -> { }
            default -> throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET);
        }
    }
}
