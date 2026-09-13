package kr.co.teambrain.marvelrun.user.payment.command.domain.repository;

import kr.co.teambrain.marvelrun.user.payment.command.domain.PaymentProcessLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentProcessLogCommandRepository extends JpaRepository<PaymentProcessLog, String> {
}
