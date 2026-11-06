package kr.co.teambrain.marvelrun.user.event.command.repository;

import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface RegistrationItemCommandRepository
        extends JpaRepository<RegistrationItem, String> {

    /** 신청의 구매 줄을 신청 안 순서로 읽는다. */
    List<RegistrationItem> findByRegistration_IdOrderBySortOrderAsc(String registrationId);

    /** 여러 신청의 구매 줄을 상품과 함께 신청·줄 순서로 읽는다. */
    @Query("""
        select i
        from RegistrationItem i
        join fetch i.eventCategory
        where i.registration.id in :registrationIds
        order by i.registration.id, i.sortOrder
        """)
    List<RegistrationItem> findAllByRegistrationIds(@Param("registrationIds") Collection<String> registrationIds);
}
