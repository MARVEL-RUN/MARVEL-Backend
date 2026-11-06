package kr.co.teambrain.marvelrun.user.event.command.repository;

import kr.co.teambrain.marvelrun.user.event.command.application.domain.policy.EventRegistrationPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EventRegistrationPolicyRepository
        extends JpaRepository<EventRegistrationPolicy, String> {

    /**
     * 대회에 연결된 참가신청 정책을 조회한다.
     *
     * 정책 누락 여부는 호출하는 검증 흐름에서 판단한다.
     */
    @Query("""
            select policy
            from EventRegistrationPolicy policy
            join fetch policy.event event
            where event.id = :eventId
            """)
    Optional<EventRegistrationPolicy> findByEventId(
            @Param("eventId") String eventId
    );
}