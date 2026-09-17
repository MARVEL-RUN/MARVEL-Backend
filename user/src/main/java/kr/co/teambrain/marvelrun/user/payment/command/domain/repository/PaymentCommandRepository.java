package kr.co.teambrain.marvelrun.user.payment.command.domain.repository;

import kr.co.teambrain.marvelrun.user.payment.command.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentCommandRepository
        extends JpaRepository<Payment, String> {

    Optional<Payment> findByOrderId(
            String orderId
    );
}