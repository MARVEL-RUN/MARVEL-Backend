package kr.co.teambrain.marvelrun.user.event.query.repository;

import kr.co.teambrain.marvelrun.common.inheritance_enum.ParticipantAuthMethod;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EventQueryRepository extends JpaRepository<Event, String> {

    /** 요청마다 거치는 접근 검사에서 대회 엔티티 전체 대신 인증 방식만 읽는다. */
    @Query("""
            select e.participantAuthMethod
            from Event e
            where e.id = :eventId
            """)
    Optional<ParticipantAuthMethod> findParticipantAuthMethodById(@Param("eventId") String eventId);
}
