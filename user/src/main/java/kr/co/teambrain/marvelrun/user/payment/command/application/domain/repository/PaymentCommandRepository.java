package kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository;

import jakarta.persistence.LockModeType;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentCommandRepository
        extends JpaRepository<Payment, String> {

    Optional<Payment> findByOrderId(
            String orderId
    );

    /**
     * 승인 시작 대상 Payment를 잠금 조회한다.
     *
     * 호출 서비스의 트랜잭션 안에서 사용하며,
     * 동일 주문에 대한 동시 승인 시작을 순차 처리한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select p
        from Payment p
        where p.orderId = :orderId
        """)
    Optional<Payment> findByOrderIdForUpdate(
            @Param("orderId") String orderId
    );

    /**
     * 승인 결과를 반영할 Payment를 잠금 조회한다.
     *
     * 성공·실패·UNKNOWN 처리 간 상태 덮어쓰기를 방지하기 위해 사용한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select p
        from Payment p
        where p.id = :paymentId
        """)
    Optional<Payment> findByIdForUpdate(
            @Param("paymentId") String paymentId
    );
}