package kr.co.teambrain.marvelrun.admin.event.command.repository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentCommandRepository extends JpaRepository<Payment, String> {
    List<Payment> findAllByRegistration_Id(String registrationId);
}