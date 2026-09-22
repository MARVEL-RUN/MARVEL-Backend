package kr.co.teambrain.marvelrun.admin.event.command.repository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategorySouvenir;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface EventCategorySouvenirCommandRepository
        extends JpaRepository<EventCategorySouvenir, String> {

    @Query("""
            select ecs
            from EventCategorySouvenir ecs
            join fetch ecs.souvenir s

            where ecs.eventCategory.id = :eventCategoryId
              and s.id in :souvenirIds
            """)
    List<EventCategorySouvenir> findSelectedMappings(
            @Param("eventCategoryId") String eventCategoryId,
            @Param("souvenirIds") Collection<String> souvenirIds
    );

    /**
     * 해당 종목에 매핑된 전체 기념품을 조회한다.
     *
     * 현재는 매핑된 기념품을 모두 신청 요청에 포함해야 하므로,
     * 요청 기념품 ID나 활성 여부로 조회 대상을 제한하지 않는다.
     */
    @Query("""
        select ecs
        from EventCategorySouvenir ecs
        join fetch ecs.souvenir
        where ecs.eventCategory.id = :eventCategoryId
        """)
    List<EventCategorySouvenir> findAllMappingsByCategoryId(
            @Param("eventCategoryId") String eventCategoryId
    );
}