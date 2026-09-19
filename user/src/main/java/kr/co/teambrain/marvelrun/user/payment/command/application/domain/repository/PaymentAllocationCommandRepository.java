package kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository;

import kr.co.teambrain.marvelrun.user.payment.command.application.domain.PaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

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
}