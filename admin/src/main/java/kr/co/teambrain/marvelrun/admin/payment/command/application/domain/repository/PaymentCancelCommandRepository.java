package kr.co.teambrain.marvelrun.admin.payment.command.application.domain.repository;

import jakarta.persistence.LockModeType;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.PaymentCancel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 원결제별 취소 시도의 조회와 상태 잠금을 제공한다. */
public interface PaymentCancelCommandRepository
        extends JpaRepository<PaymentCancel, String> {

    /** 원결제 하나의 모든 상태 취소 기록을 조회하여 한도 계산에 사용한다. */
    List<PaymentCancel> findAllByPayment_IdOrderByIdAsc(String paymentId);

    /**
     * 필요한 원 Payment 잠금 이후 기존 취소 행을 ID 순으로 보호한다.
     * 빈 조회 결과만으로 동시 INSERT를 막을 수 없으므로 부모 잠금이 선행되어야 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("""
            select c from PaymentCancel c
            where c.payment.id in :paymentIds
            order by c.id
            """)
    List<PaymentCancel> findAllByPaymentIdsForUpdate(
            @Param("paymentIds") Collection<String> paymentIds);

    /** 호출자가 정한 공통 잠금 순서 안에서 취소 결과 반영 대상을 보호한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("select c from PaymentCancel c where c.id = :cancelId")
    Optional<PaymentCancel> findByIdForUpdate(@Param("cancelId") String cancelId);
}
