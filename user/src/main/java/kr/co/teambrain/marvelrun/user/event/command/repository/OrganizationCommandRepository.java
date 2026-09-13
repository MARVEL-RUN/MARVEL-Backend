package kr.co.teambrain.marvelrun.user.event.command.repository;

import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationCommandRepository
        extends JpaRepository<Organization, String> {
}
