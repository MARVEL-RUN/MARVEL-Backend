package kr.co.teambrain.marvelrun.user.payment.command.application.valid;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 대회의 신규 결제 진행 가능 여부를 검증한다.
 *
 * 신청 기간과 수량 확보 유효기간은 이 클래스의 검증 대상이 아니다.
 * Service가 전달한 기준시각을 사용하며 현재 시각을 직접 조회하지 않는다.
 */
@Component
public class EventPaymentPolicyValidator {

    /**
     * 새로운 결제 승인 처리를 시작할 수 있는지 검증한다.
     *
     * paymentDeadline 이전까지 허용하고 정각부터 차단한다.
     * 이미 진행한 결제의 결과 확인이나 승인 결과 반영에는 사용하지 않는다.
     */
    public void validateNewPayment(
            Event event,
            LocalDateTime now
    ) {
        if (event == null || now == null) {
            throw new CustomException(
                    ErrorCode.PAYMENT_POLICY_CONFIGURATION_ERROR
            );
        }

        LocalDateTime paymentDeadline =
                event.getPaymentDeadline();

        if (paymentDeadline == null) {
            throw new CustomException(
                    ErrorCode.PAYMENT_POLICY_CONFIGURATION_ERROR
            );
        }

        if (!now.isBefore(paymentDeadline)) {
            throw new CustomException(
                    ErrorCode.EVENT_PAYMENT_CLOSED
            );
        }
    }
}