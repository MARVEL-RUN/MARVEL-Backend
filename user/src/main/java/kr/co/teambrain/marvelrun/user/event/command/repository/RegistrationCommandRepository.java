package kr.co.teambrain.marvelrun.user.event.command.repository;


import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

/** 신청 조회와 활성 중복 검사 및 단체 수정에 필요한 구성원 현재 읽기를 제공한다. */
@Repository
public interface RegistrationCommandRepository extends JpaRepository<Registration, String> {

    /** 대회에 속한 미삭제 개인 신청을 잠가 비밀번호 검증과 변경 사이의 경합을 막는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select r
        from Registration r
        where r.id = :registrationId
          and r.event.id = :eventId
          and r.organization is null
          and r.softDeleted = false
        """)
    Optional<Registration> findPersonalPasswordChangeTarget(
            @Param("eventId") String eventId,
            @Param("registrationId") String registrationId
    );

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

    /**
     * 참가자 로그인 후보. 주문자 키가 있는 개인 주문만 본다(외부 결제·관리자 등록 신청 제외).
     * 취소한 주문도 넣어 모두 취소한 주문자도 로그인해 환불 상태를 본다.
     * 살아 있는 주문, 최근 신청 순으로 돌려준다. 비밀번호는 호출부가 해시로 대조한다.
     * 공유 잠금(FOR SHARE)으로 읽는다. 비밀번호 일괄 변경은 이 행들을 쓰기 잠금하므로, 겹친 로그인은 끝날 때까지 변경을 기다리게 하거나
     * 변경이 커밋된 새 해시를 본다. 옛 비밀번호로 확인한 로그인이 변경 뒤에 refresh를 저장하지 못한다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("""
        select r from Registration r
        where r.event.id = :eventId
          and r.organization is null
          and r.externalPayment = false
          and r.ordererKey is not null
          and r.name = :name
          and r.phNum = :phNum
          and r.birth = :birth
        order by r.softDeleted asc, r.registrationDate desc, r.id
        """)
    List<Registration> findParticipantLoginCandidates(
            @Param("eventId") String eventId,
            @Param("name") String name,
            @Param("phNum") String phNum,
            @Param("birth") String birth
    );

    /** 주문자의 개인 주문 전부(취소 포함). 살아 있는 주문, 최근 신청 순이다. */
    @Query("""
        select r from Registration r
        join fetch r.event
        where r.ordererKey = :ordererKey
          and r.organization is null
          and r.externalPayment = false
        order by r.softDeleted asc, r.registrationDate desc, r.id
        """)
    List<Registration> findOrdererRegistrations(@Param("ordererKey") String ordererKey);

    /** 주문자의 개인 주문 전부를 id 순으로 잠근다. 대회 잠금 뒤에 부른다. */
    @Query("""
        select r from Registration r
        where r.ordererKey = :ordererKey
          and r.organization is null
          and r.externalPayment = false
        order by r.id
        """)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Registration> findOrdererRegistrationsForUpdate(@Param("ordererKey") String ordererKey);

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

    /**
     * 본인을 뺀 같은 신원의 살아 있는 신청 수를 잠금 읽기(FOR SHARE)로 센다.
     *
     * 같은 신청자 주문 여러 건을 허용한 대회의 신청은 DB 유니크 인덱스가 보지 않으므로,
     * 대회 행 잠금 뒤 이 쿼리로 다른 트랜잭션이 이미 커밋한 최신 행까지 본다.
     * REPEATABLE READ에서 일반 조회는 트랜잭션 첫 읽기 때의 스냅숏을 보기 때문이다.
     */
    @Query(value = """
        select count(*)
        from registration r
        where r.event_id = :eventId
          and r.id <> :registrationId
          and r.name = :name
          and r.ph_num = :phNum
          and r.birth = :birth
          and r.is_del = 0
        for share
        """, nativeQuery = true)
    long countOtherActiveByEventIdAndUniqueInfoForShare(
            @Param("eventId") String eventId,
            @Param("registrationId") String registrationId,
            @Param("name") String name,
            @Param("phNum") String phNum,
            @Param("birth") String birth
    );

    /**
     * 주문자 정보 일괄 변경 때, 바꿀 신원을 다른 주문자(또는 주문자 키가 없는 신청)가 살아 있는 주문으로 쓰는지 센다.
     * 대회 행 잠금 뒤 부른다. 같은 주문자의 주문끼리는 같은 신원이므로 세지 않는다.
     */
    @Query(value = """
        select count(*)
        from registration r
        where r.event_id = :eventId
          and (r.orderer_key is null or r.orderer_key <> :ordererKey)
          and r.name = :name
          and r.ph_num = :phNum
          and r.birth = :birth
          and r.is_del = 0
        for share
        """, nativeQuery = true)
    long countOtherOrdererActiveByIdentityForShare(
            @Param("eventId") String eventId,
            @Param("ordererKey") String ordererKey,
            @Param("name") String name,
            @Param("phNum") String phNum,
            @Param("birth") String birth
    );
}
