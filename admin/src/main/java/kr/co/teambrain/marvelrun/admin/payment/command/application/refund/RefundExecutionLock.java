package kr.co.teambrain.marvelrun.admin.payment.command.application.refund;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.PaymentCancel;
import kr.co.teambrain.marvelrun.admin.payment.command.AdminRefundLockRepository;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 환불 시작·결과 반영에 동일한 대회→단체→원결제→취소 잠금 순서를 적용한다. */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class RefundExecutionLock {
    private final EntityManager entityManager;
    private final AdminRefundLockRepository parentLocks;

    /** 단체 잠금은 Payment 조회 전에 확보하여 개인정보 수정과 대기 순환을 만들지 않는다. */
    public PaymentCancel lock(String eventId, String organizationId, String paymentId, String cancelId) {
        if (eventId == null || eventId.isBlank() || paymentId == null || cancelId == null) { throw invalid(); }
        if (!parentLocks.lockEvent(eventId)) { throw invalid(); }
        if (organizationId != null && !parentLocks.lockOrganization(eventId, organizationId)) { throw invalid(); }
        Payment payment = entityManager.find(Payment.class, paymentId);
        if (payment == null) { throw invalid(); }
        entityManager.refresh(payment, LockModeType.PESSIMISTIC_WRITE);
        // 실패 결과 반영 경로도 대회·개인/단체 귀속이 같은 실행만 허용한다.
        if ((payment.getRegistration() == null) == (payment.getOrganization() == null)) { throw invalid(); }
        if (payment.getOrganization() != null) {
            if (!java.util.Objects.equals(organizationId, payment.getOrganization().getId())
                    || !eventId.equals(payment.getOrganization().getEvent().getId())) { throw invalid(); }
        } else if (organizationId != null || payment.getRegistration().getOrganization() != null
                || !eventId.equals(payment.getRegistration().getEvent().getId())) { throw invalid(); }
        PaymentCancel cancel = entityManager.find(PaymentCancel.class, cancelId);
        if (cancel == null) { throw invalid(); }
        entityManager.refresh(cancel, LockModeType.PESSIMISTIC_WRITE);
        if (!paymentId.equals(cancel.getPayment().getId())) { throw invalid(); }
        return cancel;
    }

    /** 저장된 환불의 식별 관계가 깨졌을 때 사용할 오류이다. */
    private static CustomException invalid() {
        return new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
    }
}
