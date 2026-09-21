package kr.co.teambrain.marvelrun.admin.user.query.repository;

import kr.co.teambrain.marvelrun.admin.user.command.domain.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface OrganizationQueryRepository extends JpaRepository<Organization, String>, JpaSpecificationExecutor<Organization> {
}