package kr.co.teambrain.marvelrun.user.event.command.repository;


import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface EventCategoryCommandRepository
        extends JpaRepository<EventCategory, String> {
    /** 외적 할인 안내에 필요한 대회의 종목을 일괄 조회한다. */
    List<EventCategory> findByEvent_Id(String eventId);
}
