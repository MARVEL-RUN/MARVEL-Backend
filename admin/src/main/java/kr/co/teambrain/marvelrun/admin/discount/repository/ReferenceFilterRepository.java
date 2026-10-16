package kr.co.teambrain.marvelrun.admin.discount.repository;

import kr.co.teambrain.marvelrun.admin.discount.domain.ReferenceFilter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.List;

/** 대회별 참조필터와 관리자 선택지 검색에 필요한 조회를 제공한다. */
public interface ReferenceFilterRepository extends JpaRepository<ReferenceFilter, String>, JpaSpecificationExecutor<ReferenceFilter> {
    /** 지정 대회에 속한 필터 목록을 조회한다. */
    List<ReferenceFilter> findByEventId(String eventId);
}
