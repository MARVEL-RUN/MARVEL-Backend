package kr.co.teambrain.marvelrun.user.event.command.repository;

import java.util.Collection;
import java.util.List;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategoryStartGroup;
import org.springframework.data.jpa.repository.JpaRepository;

/** 선택지·선택 검증은 종목별 일괄 조회하고 기존 참조 조회는 활성 여부와 무관하게 제공한다. */
public interface EventCategoryStartGroupRepository extends JpaRepository<EventCategoryStartGroup, String> {
    /** 종목 목록의 활성 그룹을 앞 그룹부터 한 번에 읽는다. */
    List<EventCategoryStartGroup> findByEventCategoryIdInAndActiveTrueOrderByEventCategoryIdAscSortOrderAsc(
            Collection<String> eventCategoryIds);

    /** 기존 신청이 참조하는 비활성 그룹도 표시할 수 있도록 ID로 일괄 조회한다. */
    List<EventCategoryStartGroup> findByIdIn(Collection<String> ids);
}
