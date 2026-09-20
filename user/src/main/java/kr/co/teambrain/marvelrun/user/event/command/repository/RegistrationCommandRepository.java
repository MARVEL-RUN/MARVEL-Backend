package kr.co.teambrain.marvelrun.user.event.command.repository;


import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 신청 조회와 활성 중복 검사 및 단체 수정에 필요한 구성원 현재 읽기를 제공한다. */
@Repository
public interface RegistrationCommandRepository extends JpaRepository<Registration, String> {
    /**
     * 단체 잠금 획득 후 현재 읽기로 활성 구성원과 version을 확인하고 저장까지 보호한다.
     * 제공 DDL의 단체 인덱스로 범위를 한정하여 대회 전체 인덱스 스캔에 따른 잠금 확대를 피한다.
     */
    @Query(value = """
        select id, version from registration force index (idx_registration_organization)
        where organization_id = :organizationId and event_id = :eventId and is_del = 0
        order by id for update
        """, nativeQuery = true)
    List<Object[]> lockActiveOrganizationVersions(@Param("eventId") String eventId,
                                                 @Param("organizationId") String organizationId);

    @Query("""
        select case
                   when count(r) > 0 then true
                   else false
               end
        from Registration r
        where r.event.id = :eventId
          and r.name = :name
          and r.phNum = :phNum
          and r.birth = :birth
          and r.softDeleted = false
        """)
    boolean existsByEventIdAndUniqueInfo(
            @Param("eventId") String eventId,
            @Param("name") String name,
            @Param("phNum") String phNum,
            @Param("birth") String birth
    );

    List<Registration> findAllByOrganization_Id(
            String organizationId
    );

    /**
     * 개인 수정 대상 Registration을 조회한다.
     *
     * 지정 Event 소속이며 단체 신청이 아니고,
     * 삭제되지 않은 Registration만 수정 대상으로 인정한다.
     */
    @Query("""
        select r
        from Registration r
        join fetch r.event e
        join fetch r.eventCategory ec
        where r.id = :registrationId
          and e.id = :eventId
          and r.organization is null
          and r.softDeleted = false
        """)
    Optional<Registration> findActivePersonalModificationTarget(
            @Param("eventId") String eventId,
            @Param("registrationId") String registrationId
    );


    /**
     * 지정 Event / Organization에 현재 소속된
     * 삭제되지 않은 Registration 전체를 조회한다.
     *
     * 수정 전 구성원 snapshot 및 최종목록 diff의 기준으로 사용한다.
     */
    @Query("""
        select r
        from Registration r
        join fetch r.event e
        join fetch r.eventCategory ec
        join fetch r.organization o
        where e.id = :eventId
          and o.id = :organizationId
          and r.softDeleted = false
        order by r.id
        """)
    List<Registration> findAllActiveByEventAndOrganization(
            @Param("eventId") String eventId,
            @Param("organizationId") String organizationId
    );

    /**
     * 기존 Registration 수정 시 자기 자신을 제외하고
     * 동일 Event에 같은 참가자 식별정보를 가진 활성 신청이 존재하는지 확인한다.
     */
    @Query("""
        select case
            when count(r) > 0 then true
            else false
        end
        from Registration r
        where r.event.id = :eventId
          and r.id <> :registrationId
          and r.name = :name
          and r.phNum = :phNum
          and r.birth = :birth
          and r.softDeleted = false
        """)
    boolean existsOtherActiveByEventIdAndUniqueInfo(
            @Param("eventId") String eventId,
            @Param("registrationId") String registrationId,
            @Param("name") String name,
            @Param("phNum") String phNum,
            @Param("birth") String birth
    );
}
