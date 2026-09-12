package kr.co.teambrain.marvelrun.user.event.command.application.log.repository;

import kr.co.teambrain.marvelrun.user.event.command.application.log.domain.PaymentProcessLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentProcessLogCommandRepository extends JpaRepository<PaymentProcessLog, String> {
}
