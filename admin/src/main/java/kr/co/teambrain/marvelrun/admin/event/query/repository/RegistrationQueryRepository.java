package kr.co.teambrain.marvelrun.admin.event.query.repository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationStatDto;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RegistrationQueryRepository extends JpaRepository<Registration, String>, JpaSpecificationExecutor<Registration> {

    // 특정 단체의 유효한(softDeleted=false) 회원 수 카운트
    long countByOrganizationIdAndSoftDeletedFalse(String organizationId);

    @EntityGraph(attributePaths = {"eventCategory"})
    List<Registration> findByOrganizationIdAndSoftDeletedFalse(String organizationId);

    // 기존 코드 하단에 추가
    @Query("""
        select new kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationStatDto(
            r.status, o.id, r.gender, r.birth
        )
        from Registration r
        left join r.organization o
        where r.event.id = :eventId
          and r.softDeleted = false
        """)
    List<RegistrationStatDto> findStatsByEventId(@Param("eventId") String eventId);
}
