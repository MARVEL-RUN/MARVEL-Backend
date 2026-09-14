package kr.co.teambrain.marvelrun.user.event.query.repository;

import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EventCategoryQueryRepository extends JpaRepository<EventCategory, String> {

    List<EventCategory> findAllByEvent_IdAndIsActiveTrue(
            String eventId
    );
}
