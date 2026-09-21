package kr.co.teambrain.marvelrun.admin.event.command.repository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RegistrationCommandRepository extends JpaRepository<Registration, String> {

    List<Registration> findAllByOrganization_Id(String organizationId);
}
