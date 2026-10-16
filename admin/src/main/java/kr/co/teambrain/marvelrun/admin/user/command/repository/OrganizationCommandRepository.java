package kr.co.teambrain.marvelrun.admin.user.command.repository;

import kr.co.teambrain.marvelrun.admin.user.command.application.domain.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrganizationCommandRepository extends JpaRepository<Organization, String> {

    /** 활성 단체만 중복으로 취급한다. */
    @Query("select (count(o) > 0) from Organization o where o.groupName = ?1 and o.event.id = ?2 and o.softDeleted = false")
    boolean existsByGroupNameAndEventId(String groupName, String eventId);

    /** 삭제된 과거 단체와 아이디를 재사용할 수 있다. */
    @Query("select (count(o) > 0) from Organization o where o.loginId = ?1 and o.event.id = ?2 and o.softDeleted = false")
    boolean existsByLoginIdAndEventId(String loginId, String eventId);
}
