package kr.co.teambrain.marvelrun.user.event.command.repository;

import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

/** 단체 계정의 대회 귀속 조회와 비밀번호 변경용 행 잠금을 제공한다. */
public interface OrganizationCommandRepository
        extends JpaRepository<Organization, String> {

    /** 구성원 삭제 여부와 관계없이 해당 대회의 단체 계정을 잠가 최신 비밀번호를 읽는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select o
        from Organization o
        where o.id = :organizationId
          and o.event.id = :eventId
        """)
    Optional<Organization> findPasswordChangeTarget(
            @Param("eventId") String eventId,
            @Param("organizationId") String organizationId
    );


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
