package kr.co.teambrain.marvelrun.admin.event.command.repository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface RegistrationCommandRepository extends JpaRepository<Registration, String> {

    /** 업로드 배치의 이름으로 후보를 조회하고 전화번호·출생일은 호출부에서 대조한다. */
    @Query("select r from Registration r where r.event.id=:eventId and r.softDeleted=false and r.name in :names")
    List<Registration> findActiveByEventAndNames(@Param("eventId") String eventId,
                                                @Param("names") List<String> names);

    List<Registration> findAllByOrganization_Id(String organizationId);

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
}
