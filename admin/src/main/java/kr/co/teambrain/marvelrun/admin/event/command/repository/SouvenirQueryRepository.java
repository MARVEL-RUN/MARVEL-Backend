package kr.co.teambrain.marvelrun.admin.event.command.repository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Souvenir;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SouvenirQueryRepository extends JpaRepository<Souvenir, String> {
    /** 기념품 이름 매핑에 필요한 값만 조회한다. */
    interface SouvenirNameProjection {
        String getId();
        String getName();
    }

    /** 기념품 ID 목록에 해당하는 이름을 일괄 조회한다. */
    @Query("""
        select s.id as id, s.name as name
        from Souvenir s
        where s.id in :ids
        """)
    List<SouvenirNameProjection> findNamesByIds(@Param("ids") List<String> ids);
}
