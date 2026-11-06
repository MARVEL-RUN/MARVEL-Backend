package kr.co.teambrain.marvelrun.admin.event.query.repository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.RegistrationEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/** 신청 상세·목록 배번에 보여 줄 참가 항목을 신청 ID 묶음으로 한 번에 읽는다. */
public interface RegistrationEntryQueryRepository extends JpaRepository<RegistrationEntry, String> {

    /** 종목과 구매 줄(상품 종목 포함)을 함께 가져오고 구매 줄 순서, 하위 종목 정렬 순서, 같으면 ID 순으로 돌려준다. */
    @Query("""
            select e from RegistrationEntry e
            join fetch e.eventCategory c
            join fetch e.registrationItem i
            join fetch i.eventCategory
            where e.registration.id in :registrationIds
            order by i.sortOrder asc, c.order asc, e.id asc
            """)
    List<RegistrationEntry> findByRegistrationIds(@Param("registrationIds") Collection<String> registrationIds);
}
