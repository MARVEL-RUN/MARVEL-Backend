package kr.co.teambrain.marvelrun.admin.discount.repository;

import kr.co.teambrain.marvelrun.admin.discount.domain.ReferenceFilter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.List;


/** 할인 구성 저장소이며 관리용 쓰기 API는 제공하지 않는다. */
public interface ReferenceFilterRepository extends JpaRepository<ReferenceFilter, String>, JpaSpecificationExecutor<ReferenceFilter> {
    List<ReferenceFilter> findByEventId(String eventId);
}