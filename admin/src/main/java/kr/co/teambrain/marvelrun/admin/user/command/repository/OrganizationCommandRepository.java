package kr.co.teambrain.marvelrun.admin.user.command.repository;

import kr.co.teambrain.marvelrun.admin.user.command.application.domain.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationCommandRepository extends JpaRepository<Organization, String> {

    boolean existsByGroupNameAndEventId(String groupName, String eventId);

    boolean existsByLoginIdAndEventId(String loginId, String eventId);
}
