package kr.co.teambrain.marvelrun.admin.payment.command.application.domain.repository;

import kr.co.teambrain.marvelrun.admin.payment.command.application.domain.PaymentCancelAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

/** 변경하지 않는 취소 귀속 행을 저장하고 취소별·원귀속별로 조회한다. */
public interface PaymentCancelAllocationCommandRepository
        extends JpaRepository<PaymentCancelAllocation, String> {

    /** 기존 취소에 귀속을 다시 추가하지 않도록 저장 여부를 확인한다. */
    boolean existsByPaymentCancel_Id(String cancelId);

    /** 한 취소 결과를 참가자별로 반영할 때 사용할 귀속을 조회한다. */
    @Query("""
            select a from PaymentCancelAllocation a
            join fetch a.originalAllocation original
            join fetch original.registration
            where a.paymentCancel.id = :cancelId
            order by original.id
            """)
    List<PaymentCancelAllocation> findAllByCancelId(
            @Param("cancelId") String cancelId);

    /**
     * 원 Allocation의 전체 취소 귀속과 부모 상태를 한도 계산용으로 조회한다.
     * 삭제된 신청의 환불도 포함해야 하므로 활성 신청 조건을 붙이지 않는다.
     */
    @Query("""
            select a from PaymentCancelAllocation a
            join fetch a.paymentCancel c
            where a.originalAllocation.id = :allocationId
            order by c.id, a.id
            """)
    List<PaymentCancelAllocation> findAllByOriginalAllocationId(
            @Param("allocationId") String allocationId);

    /** 부모 Payment·PaymentCancel 잠금 뒤 변경 불가능한 과거 취소 귀속을 현재 읽기로 조회한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("select a from PaymentCancelAllocation a where a.paymentCancel.payment.id = :paymentId order by a.id")
    List<PaymentCancelAllocation> findAllForRefund(@Param("paymentId") String paymentId);
}