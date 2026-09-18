package kr.co.teambrain.marvelrun.user.event.command.repository;

import jakarta.persistence.LockModeType;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 대회 조회와 저장을 담당한다.
 *
 * 신규 신청에서는 대회 행을 먼저 잠근 뒤 신청과 정원을 처리하여,
 * 신청 생성과 정원 마감의 처리 순서를 일관되게 유지한다.
 */
@Repository
public interface EventCommandRepository
        extends JpaRepository<Event, String> {

    /**
     * 신규 신청을 처리할 대회를 쓰기 잠금으로 조회한다.
     *
     * 신청 및 단체 엔티티를 저장하기 전에 호출한다.
     * 잠금은 호출 서비스의 트랜잭션이 끝날 때 해제된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("""
            select e
            from Event e
            where e.id = :eventId
            """)
    Optional<Event> findByIdForUpdate(
            @Param("eventId") String eventId
    );
}