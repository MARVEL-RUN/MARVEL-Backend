package kr.co.teambrain.marvelrun.user.event.command.repository;

import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrganizationCommandRepository
        extends JpaRepository<Organization, String> {

    /**
     * 지정 Event에 소속된 수정 대상 Organization을 조회한다.
     *
     * Organization ID만으로 조회하지 않고 Event 귀속까지 함께 검증한다.
     */
    @Query("""
        select o
        from Organization o
        join fetch o.event e
        where o.id = :organizationId
          and e.id = :eventId
        """)
    Optional<Organization> findModificationTarget(
            @Param("eventId") String eventId,
            @Param("organizationId") String organizationId
    );

    boolean existsByGroupNameAndEventId(String groupName, String eventId);

    boolean existsByLoginIdAndEventId(String loginId, String eventId);
}
