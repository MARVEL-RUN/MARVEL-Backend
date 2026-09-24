package kr.co.teambrain.marvelrun.admin.event.query.repository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategory; // 관리자 엔티티 경로[cite: 14]
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface EventCategoryQueryRepository extends JpaRepository<EventCategory, String> {

    /**
     * 특정 대회(eventId)에 속한 코스 목록을 정렬 순서(order)에 따라 오름차순으로 조회합니다.[cite: 15]
     */
    List<EventCategory> findAllByEvent_IdOrderByOrderAsc(String eventId);

    List<EventCategory> findAllByEvent_IdOrderByOrderDesc(String eventid);
}