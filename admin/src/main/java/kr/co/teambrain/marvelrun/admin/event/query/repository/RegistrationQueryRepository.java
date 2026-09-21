package kr.co.teambrain.marvelrun.admin.event.query.repository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface RegistrationQueryRepository extends JpaRepository<Registration, String>, JpaSpecificationExecutor<Registration> {

    // 특정 단체의 유효한(softDeleted=false) 회원 수 카운트
    long countByOrganizationIdAndSoftDeletedFalse(String organizationId);

    @EntityGraph(attributePaths = {"eventCategory"})
    List<Registration> findByOrganizationIdAndSoftDeletedFalse(String organizationId);
}
