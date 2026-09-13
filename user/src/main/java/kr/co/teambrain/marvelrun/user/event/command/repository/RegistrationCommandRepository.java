package kr.co.teambrain.marvelrun.user.event.command.repository;


import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RegistrationCommandRepository extends JpaRepository<Registration, String> {
    @Query("""
        select case
                   when count(r) > 0 then true
                   else false
               end
        from Registration r
        where r.event.id = :eventId
          and r.name = :name
          and r.phNum = :phNum
          and r.birth = :birth
        """)
    boolean existsByEventIdAndUniqueInfo(
            @Param("eventId") String eventId,
            @Param("name") String name,
            @Param("phNum") String phNum,
            @Param("birth") String birth
    );

    List<Registration> findAllByOrganization_Id(
            String organizationId
    );
}
