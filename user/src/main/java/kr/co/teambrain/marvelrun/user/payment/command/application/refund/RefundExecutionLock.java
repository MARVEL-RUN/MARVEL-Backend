package kr.co.teambrain.marvelrun.user.payment.command.application.refund;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.PaymentCancel;
import kr.co.teambrain.marvelrun.user.event.command.application.support.OrganizationLockSupport;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
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

    /** 단체 잠금은 Payment 조회 전에 확보하여 개인정보 수정과 대기 순환을 만들지 않는다. */
    public PaymentCancel lock(String eventId, String organizationId, String paymentId, String cancelId) {
        if (entityManager.createNativeQuery("select id from event where id = :id for update")
                .setParameter("id", eventId).getResultList().isEmpty()) { throw invalid(); }
        if (organizationId != null) {
            OrganizationLockSupport.lockWithoutWaiting(entityManager, organizationId);
        }
        Payment payment = entityManager.find(Payment.class, paymentId);
        if (payment == null) { throw invalid(); }
        entityManager.refresh(payment, LockModeType.PESSIMISTIC_WRITE);
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
