package kr.co.teambrain.marvelrun.admin.event.query.repository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentQueryRepository extends JpaRepository<Payment, String> {

    Optional<Payment> findFirstByRegistrationIdOrderByCreatedAtDesc(String registrationId);

    Optional<Payment> findFirstByOrganization_IdOrderByCreatedAtDesc(String organizationId);
}
