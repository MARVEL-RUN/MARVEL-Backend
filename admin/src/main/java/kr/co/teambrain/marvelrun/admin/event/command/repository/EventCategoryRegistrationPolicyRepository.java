package kr.co.teambrain.marvelrun.admin.event.command.repository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.policy.EventCategoryRegistrationPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface EventCategoryRegistrationPolicyRepository
        extends JpaRepository<EventCategoryRegistrationPolicy, String> {

    /**
     * 해당 대회에 속한 단일 종목의 출생일 정책을 조회한다.
     *
     * 다른 대회의 종목 정책이 조회되지 않도록 eventId도 확인한다.
     */
    @Query("""
            select policy
            from EventCategoryRegistrationPolicy policy
            join fetch policy.eventCategory category
            where category.event.id = :eventId
              and category.id = :eventCategoryId
            """)
    Optional<EventCategoryRegistrationPolicy> findByEventIdAndCategoryId(
            @Param("eventId") String eventId,
            @Param("eventCategoryId") String eventCategoryId
    );

    /**
     * 단체 신청에 포함된 여러 종목의 정책을 한 번에 조회한다.
     *
     * 호출부는 중복을 제거한 종목 ID 목록을 전달한다.
     * 빈 목록이면 호출부에서 조회를 생략한다.
     * 조회 결과에 없는 종목은 호출부에서 정책 누락으로 판단한다.
     */
    @Query("""
            select policy
            from EventCategoryRegistrationPolicy policy
            join fetch policy.eventCategory category
            where category.event.id = :eventId
              and category.id in :eventCategoryIds
            """)
    List<EventCategoryRegistrationPolicy> findAllByEventIdAndCategoryIds(
            @Param("eventId") String eventId,
            @Param("eventCategoryIds") Collection<String> eventCategoryIds
    );
}