package kr.co.teambrain.marvelrun.admin.payment.command.application.domain.repository;

import kr.co.teambrain.marvelrun.admin.payment.command.application.domain.PaymentProcessLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentProcessLogCommandRepository extends JpaRepository<PaymentProcessLog, String> {
}
