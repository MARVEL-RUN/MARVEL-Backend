package kr.co.teambrain.marvelrun.user.payment.command.application;

import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.PaymentCancel;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * 잠긴 금융 범위에서 다른 진행 중·결과불명 거래를 검사한다.
 * 조회·잠금·READY 무효화·외부 호출은 수행하지 않는다.
 */
@Component
public class PaymentFinancialConflictGuard {
    /**
     * 자기 결과 반영 시에만 해당 거래 ID를 제외할 수 있다.
     * 신규 준비 요청은 두 제외 ID를 모두 null로 전달해야 한다.
     */
    public void validate(List<Payment> payments, List<PaymentCancel> cancellations,
                         String excludedPaymentId, String excludedCancelId) {

        if (payments == null || cancellations == null) {
            throw conflict();
        }

        for (Payment payment : payments) {
            if (payment == null || payment.getId() == null || payment.getProcessStatus() == null) {
                throw conflict();
            }
            if (payment.getId().equals(excludedPaymentId)) {
                continue;
            }
            if (payment.getProcessStatus() == PaymentProcessStatus.CONFIRMING
                    || payment.getProcessStatus() == PaymentProcessStatus.UNKNOWN) {
                throw conflict();
            }
        }

        for (PaymentCancel cancellation : cancellations) {
            if (cancellation == null || cancellation.getId() == null || cancellation.getStatus() == null) {
                throw conflict();
            }
            if (cancellation.getId().equals(excludedCancelId)) {
                continue;
            }
            if (cancellation.getStatus() == PaymentCancelStatus.PROCESSING
                    || cancellation.getStatus() == PaymentCancelStatus.UNKNOWN) {
                throw conflict();
            }
        }
    }

    /** 신규 금융 요청과 진행 거래의 충돌을 알린다. */
    private CustomException conflict() {
        return new CustomException(ErrorCode.PAYMENT_ADJUSTMENT_CONFLICT);
    }
}