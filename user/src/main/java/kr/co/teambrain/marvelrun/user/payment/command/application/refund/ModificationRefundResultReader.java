package kr.co.teambrain.marvelrun.user.payment.command.application.refund;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult.Member;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult.Refund;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 외부 처리 이후 저장된 결과를 읽어 기존 수정 응답에 반영한다. */
@Service
@RequiredArgsConstructor
public class ModificationRefundResultReader {
    private final EntityManager entityManager;

    /** 엔티티 1차 캐시 대신 스칼라 조회로 최종 금액과 실제 저장된 환불 상태를 반환한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public RegistrationModificationSettlementResult read(RegistrationModificationSettlementResult prepared) {
        List<Member> members = new ArrayList<>();
        for (Member member : prepared.members()) {
            List<Object[]> rows = entityManager.createQuery(
                            "select r.status, r.contractAmount, r.paidAmount from Registration r where r.id = :id", Object[].class)
                    .setParameter("id", member.registrationId()).getResultList();
            if (rows.size() != 1) { throw new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR); }
            Object[] row = rows.get(0);
            BigDecimal contract = (BigDecimal) row[1];
            BigDecimal paid = (BigDecimal) row[2];
            members.add(new Member(member.registrationId(), (RegistrationStatus) row[0], contract, paid, contract.subtract(paid)));
        }
        List<Refund> refunds = new ArrayList<>();
        for (Refund refund : prepared.refunds()) {
            List<Object[]> rows = entityManager.createQuery(
                            "select c.payment.id, c.cancelAmount, c.status from PaymentCancel c where c.id = :id", Object[].class)
                    .setParameter("id", refund.paymentCancelId()).getResultList();
            if (rows.size() != 1 || !refund.paymentId().equals(rows.get(0)[0])) {
                throw new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
            }
            Object[] row = rows.get(0);
            refunds.add(new Refund(refund.paymentCancelId(), (String) row[0], (BigDecimal) row[1],
                    (PaymentCancelStatus) row[2], refund.correlationId()));
        }
        return new RegistrationModificationSettlementResult(members, prepared.orders(), refunds);
    }
}