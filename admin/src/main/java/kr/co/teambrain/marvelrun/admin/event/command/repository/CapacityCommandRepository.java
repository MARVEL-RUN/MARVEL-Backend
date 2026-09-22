package kr.co.teambrain.marvelrun.admin.event.command.repository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Capacity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CapacityCommandRepository extends JpaRepository<Capacity, String> {
    // 동시성 환경에서 Lost Update를 방지하기 위해 DB 원자적 연산을 수행합니다.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Capacity c SET c.heldCount = c.heldCount - :quantity WHERE c.id = :id AND c.heldCount >= :quantity")
    int decreaseHeldCount(@Param("id") String id, @Param("quantity") int quantity);
}