package kr.co.teambrain.marvelrun.admin.event.command.repository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.RegistrationItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 구매 줄 조회. 사용자 서버 참조 파일: user/.../event/command/repository/RegistrationItemCommandRepository.java
 * 부분 취소한 줄(canceled_at)은 지우지 않고 남기므로 지금 구매 내용을 볼 때는 active 조회를 쓴다.
 */
public interface RegistrationItemCommandRepository extends JpaRepository<RegistrationItem, String> {

    /** 신청의 살아 있는 구매 줄을 신청 안 순서로 읽는다. */
    @Query("""
        select i
        from RegistrationItem i
        join fetch i.eventCategory
        where i.registration.id = :registrationId
          and i.canceledAt is null
        order by i.sortOrder
        """)
    List<RegistrationItem> findActiveByRegistrationId(@Param("registrationId") String registrationId);
}
