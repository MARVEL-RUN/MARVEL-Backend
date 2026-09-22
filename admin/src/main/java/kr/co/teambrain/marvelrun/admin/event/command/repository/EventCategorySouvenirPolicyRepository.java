package kr.co.teambrain.marvelrun.admin.event.command.repository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.policy.EventCategorySouvenirPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface EventCategorySouvenirPolicyRepository
        extends JpaRepository<EventCategorySouvenirPolicy, String> {

    /**
     * 해당 대회의 종목-기념품 매핑에 연결된 사이즈 정책을 조회한다.
     *
     * 한 매핑에 여러 출생일 조건의 정책이 존재할 수 있으므로
     * 단일 객체가 아닌 목록을 반환한다.
     */
    @Query("""
            select policy
            from EventCategorySouvenirPolicy policy
            join fetch policy.eventCategorySouvenir mapping
            join fetch mapping.eventCategory category
            join fetch mapping.souvenir souvenir
            where category.event.id = :eventId
              and souvenir.event.id = :eventId
              and mapping.id = :mappingId
            """)
    List<EventCategorySouvenirPolicy> findAllByEventIdAndMappingId(
            @Param("eventId") String eventId,
            @Param("mappingId") String mappingId
    );

    /**
     * 여러 종목-기념품 매핑의 사이즈 정책을 한 번에 조회한다.
     *
     * 호출부는 중복을 제거한 매핑 ID 목록을 전달한다.
     * 빈 목록이면 호출부에서 조회를 생략한다.
     *
     * 정책이 없는 매핑은 조회 결과에 포함되지 않는다.
     */
    @Query("""
            select policy
            from EventCategorySouvenirPolicy policy
            join fetch policy.eventCategorySouvenir mapping
            join fetch mapping.eventCategory category
            join fetch mapping.souvenir souvenir
            where category.event.id = :eventId
              and souvenir.event.id = :eventId
              and mapping.id in :mappingIds
            """)
    List<EventCategorySouvenirPolicy> findAllByEventIdAndMappingIds(
            @Param("eventId") String eventId,
            @Param("mappingIds") Collection<String> mappingIds
    );
}