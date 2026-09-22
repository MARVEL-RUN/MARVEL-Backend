package kr.co.teambrain.marvelrun.admin.payment.command.application.domain.repository;

import kr.co.teambrain.marvelrun.admin.payment.command.application.domain.PaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

/**
 * Payment의 Registration별 금액 귀속 내역을 저장하고 조회한다.
 */
public interface PaymentAllocationCommandRepository
        extends JpaRepository<
        PaymentAllocation,
        String> {

    /**
     * 하나의 Payment에 귀속된 Allocation 전체를
     * Registration ID 순서로 조회한다.
     *
     * @param paymentId 조회할 Payment ID
     * @return Registration별 금액 귀속 목록
     */
    List<PaymentAllocation>
    findAllByPayment_IdOrderByRegistration_IdAsc(
            String paymentId
    );

    /** 부모 Payment를 잠근 뒤 귀속을 현재 읽기로 조회하여 재결제 직후 생성된 행도 확인한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("select a from PaymentAllocation a where a.payment.id = :paymentId order by a.id")
    List<PaymentAllocation> findAllForPaymentUpdate(@Param("paymentId") String paymentId);

    /** 기존 환불 호출 계약을 보존하고 공통 현재 읽기로 위임한다. */
    default List<PaymentAllocation> findAllForRefund(String paymentId) {
        return findAllForPaymentUpdate(paymentId);
    }
}
