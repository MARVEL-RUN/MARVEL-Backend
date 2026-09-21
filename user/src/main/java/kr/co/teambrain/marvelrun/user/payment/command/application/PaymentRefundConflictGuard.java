package kr.co.teambrain.marvelrun.user.payment.command.application;

import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.PaymentCancel;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentCancelCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** 잠긴 관련 Payment 범위에 남은 미확정 환불이 있으면 새로운 금융 변경을 차단한다. */
@Component
@RequiredArgsConstructor
public class PaymentRefundConflictGuard {
    private final PaymentCancelCommandRepository cancelRepository;

    /** 부모 Payment 전체를 먼저 잠근 신규 업무 진입점에서 호출한다. 결과 반영용 메서드가 아니다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void validate(List<Payment> payments) {
        if (payments.isEmpty()) { return; }
        List<String> ids = payments.stream().map(Payment::getId).distinct().sorted().toList();
        for (PaymentCancel cancellation : cancelRepository.findAllByPaymentIdsForUpdate(ids)) {
            if (cancellation.getStatus() == null) {
                throw new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
            }
            if (cancellation.getStatus() == PaymentCancelStatus.PROCESSING
                    || cancellation.getStatus() == PaymentCancelStatus.UNKNOWN) {
                throw new CustomException(ErrorCode.PAYMENT_CANCEL_CONFLICT);
            }
        }
    }
}