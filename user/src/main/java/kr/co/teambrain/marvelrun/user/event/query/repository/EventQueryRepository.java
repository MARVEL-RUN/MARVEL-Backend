package kr.co.teambrain.marvelrun.user.event.query.repository;

import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EventQueryRepository extends JpaRepository<Event, String> {
}
