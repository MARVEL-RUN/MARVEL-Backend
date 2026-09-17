package kr.co.teambrain.marvelrun.user.event.query.repository;


import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategorySouvenir;
import kr.co.teambrain.marvelrun.user.event.query.repository.projection.RegistrationOptionProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EventCategorySouvenirQueryRepository extends JpaRepository<EventCategorySouvenir, String> {

    @Query("""
            select ecs
            from EventCategorySouvenir ecs
            join fetch ecs.eventCategory ec
            join fetch ecs.souvenir s
            where ec.event.id = :eventId
              and s.event.id = :eventId
              and ec.isActive = true
              and s.isActive = true
            order by ec.name asc, s.name asc
            """)
    List<EventCategorySouvenir> findRegistrationOptions(
            @Param("eventId") String eventId
    );

    /** eventId를 기반으로, front에서 대회별 신청 구성 시 필요로 하는 종목/기념품/종목-기념품매핑 을 확인하는 projection read.  */
    @Query("""
        select
            ec.order as eventCategoryOrder,
            ec.id as eventCategoryId,
            ec.name as eventCategoryName,
            ec.amount as eventCategoryAmount,
            ec.isActive as eventCategoryIsActive,

            s.order as souvenirOrder,
            s.id as souvenirId,
            s.name as souvenirName,
            s.sizes as souvenirSizes,
            s.isActive as souvenirIsActive

        from EventCategorySouvenir ecs

        join ecs.eventCategory ec
        join ecs.souvenir s

        where ec.event.id = :eventId
          and s.event.id = :eventId

        order by
            ec.order asc,
            ec.id asc,
            s.order asc,
            s.id asc
        """)
    List<RegistrationOptionProjection> findRegistrationOptionsProjectionByEventId(
            @Param("eventId") String eventId
    );

}
