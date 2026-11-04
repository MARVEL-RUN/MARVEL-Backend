package kr.co.teambrain.marvelrun.admin.event.command.repository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.RegistrationEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/*
 * 사용자 서버 참조 파일: user/.../event/command/repository/RegistrationEntryCommandRepository.java
 */
public interface RegistrationEntryCommandRepository
        extends JpaRepository<RegistrationEntry, String> {

    List<RegistrationEntry> findByRegistration_Id(String registrationId);
}
