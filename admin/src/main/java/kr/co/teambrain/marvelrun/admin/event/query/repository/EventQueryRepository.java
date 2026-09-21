package kr.co.teambrain.marvelrun.admin.event.query.repository;

import kr.co.teambrain.marvelrun.admin.event.command.domain.Event;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventQueryRepository extends JpaRepository<Event, String> {

    // 접수 시작일을 기준으로 내림차순 정렬하여 최신 대회가 위로 오도록 전체 목록을 조회합니다.
    List<Event> findAllByOrderByRegistStartDateDesc();
}
