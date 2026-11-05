package kr.co.teambrain.marvelrun.user.event.command.repository;

import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RegistrationEntryCommandRepository
        extends JpaRepository<RegistrationEntry, String> {

    List<RegistrationEntry> findByRegistration_Id(String registrationId);

    /** 여러 신청의 참가 항목. 구매 줄마다 사람 수를 셀 때 쓴다. */
    List<RegistrationEntry> findByRegistration_IdIn(Collection<String> registrationIds);
}
