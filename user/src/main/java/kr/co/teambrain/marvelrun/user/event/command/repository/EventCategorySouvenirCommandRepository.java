package kr.co.teambrain.marvelrun.user.event.command.repository;

import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategorySouvenir;
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
}