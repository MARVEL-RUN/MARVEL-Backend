package kr.co.teambrain.marvelrun.user.event.command.application.valid;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 개인·단체 개인정보 정정에 공통인 기간과 신청 상태만 검사한다. 추가 정책 조회는 하지 않는다. */
@Component
@RequiredArgsConstructor
public class RegistrationInformationPolicyValidator {
    private final RegistrationPolicyValidator periodValidator;

    /** 이미 조회한 대회의 OPEN 상태와 접수 기간만 확인한다. */
    public void validatePeriod(Event event, LocalDateTime now) {
        periodValidator.validateNewApplication(event, now);
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
