package kr.co.teambrain.marvelrun.admin.event.command.repository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EventCommandRepository extends JpaRepository<Event,String> {

    /** 신규 신청은 사용자 서버와 동일한 대회 행부터 잠근다. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Event e where e.id=:eventId")
    Optional<Event> findByIdForUpdate(@org.springframework.data.repository.query.Param("eventId") String eventId);

}
