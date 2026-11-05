package kr.co.teambrain.marvelrun.user.event.command.repository;

import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RegistrationEntryCommandRepository
        extends JpaRepository<RegistrationEntry, String> {

    List<RegistrationEntry> findByRegistration_Id(String registrationId);
}
