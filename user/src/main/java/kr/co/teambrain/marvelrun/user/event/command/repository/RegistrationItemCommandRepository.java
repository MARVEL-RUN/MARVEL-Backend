package kr.co.teambrain.marvelrun.user.event.command.repository;

import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * 구매 줄 조회. 부분 취소한 줄(canceled_at)은 지우지 않고 남기므로,
 * 가격·정원·주문명·상향처럼 지금 구매 내용을 보는 곳은 active 조회를 쓴다.
 */
public interface RegistrationItemCommandRepository
        extends JpaRepository<RegistrationItem, String> {

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

    /** 여러 신청의 살아 있는 구매 줄을 상품과 함께 신청·줄 순서로 읽는다. */
    @Query("""
        select i
        from RegistrationItem i
        join fetch i.eventCategory
        where i.registration.id in :registrationIds
          and i.canceledAt is null
        order by i.registration.id, i.sortOrder
        """)
    List<RegistrationItem> findActiveByRegistrationIds(@Param("registrationIds") Collection<String> registrationIds);

    /** 취소한 줄까지 포함한 구매 줄. 마이페이지처럼 무엇을 취소했는지 보여 주는 조회만 쓴다. */
    @Query("""
        select i
        from RegistrationItem i
        join fetch i.eventCategory
        where i.registration.id in :registrationIds
        order by i.registration.id, i.sortOrder
        """)
    List<RegistrationItem> findAllByRegistrationIds(@Param("registrationIds") Collection<String> registrationIds);
}
