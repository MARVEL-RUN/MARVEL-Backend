package kr.co.teambrain.marvelrun.user.event.command.repository;


import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RegistrationCommandRepository extends JpaRepository<Registration, String> {
}
