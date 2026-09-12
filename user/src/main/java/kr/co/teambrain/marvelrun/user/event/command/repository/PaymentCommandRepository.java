package kr.co.teambrain.marvelrun.user.event.command.repository;

import kr.co.teambrain.marvelrun.user.event.command.application.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentCommandRepository
        extends JpaRepository<Payment, String> {
}