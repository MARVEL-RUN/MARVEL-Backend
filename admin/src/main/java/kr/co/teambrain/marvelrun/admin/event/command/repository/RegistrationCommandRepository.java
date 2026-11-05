package kr.co.teambrain.marvelrun.admin.event.command.repository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RegistrationCommandRepository extends JpaRepository<Registration, String> {

    /** 업로드 배치의 이름으로 후보를 조회하고 전화번호·출생일은 호출부에서 대조한다. */
    @Query("select r from Registration r where r.event.id=:eventId and r.softDeleted=false and r.name in :names")
    List<Registration> findActiveByEventAndNames(@Param("eventId") String eventId,
                                                @Param("names") List<String> names);

    List<Registration> findAllByOrganization_Id(String organizationId);

    /** 받는 사람 수정처럼 참가 항목을 바꿀 때 사용자 서버(참가자 받는 사람 수정)와 같은 신청 행을 잠근다. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Registration r where r.id = :registrationId")
    Optional<Registration> findByIdForUpdate(@Param("registrationId") String registrationId);

    /**
     * 관리자 수정 시, 본인을 제외한 다른 활성(softDeleted = false) 참가자 중
     * 동일한 복합 유니크 정보(이름, 연락처, 생년월일)를 가진 사람이 있는지 검증합니다.
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
}
