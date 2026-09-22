package kr.co.teambrain.marvelrun.admin.event.command.repository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.PaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// PaymentAllocationCommandRepository.java
public interface PaymentAllocationCommandRepository extends JpaRepository<PaymentAllocation, String> {
    List<PaymentAllocation> findAllByRegistration_Id(String registrationId);
    boolean existsByPayment_Id(String paymentId);
}