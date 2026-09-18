package kr.co.teambrain.marvelrun.user.capacity.command.repository;

import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.CapacityType;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Capacity;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 신청에 필요한 Capacity 조회와 원자적 수량 변경을 담당한다.
 *
 * 아래 선언한 메서드는 서비스가 시작한 트랜잭션 안에서 호출한다.
 * 여러 Capacity의 변경을 하나의 트랜잭션으로 묶는 책임은 서비스에 있다.
 *
 * 수량 변경 결과가 0이면 서비스에서 원인을 구분하여 예외를 발생시킨다.
 */
@Transactional(propagation = Propagation.MANDATORY)
public interface CapacityCommandRepository
        extends JpaRepository<Capacity, String> {

    /**
     * 대회 전체 정원과 신청 종목에 연결된 정원 후보를 조회한다.
     *
     * 어린이 정원 적용 여부는 서비스에서 참가자 판정 결과로 결정한다.
     * 비활성 Capacity도 반환하여 설정 누락과 신규 확보 차단을 구분한다.
     */
    @Query("""
            select new kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityTarget(
                c.id, c.type, s.id, c.size
            )
            from Capacity c
            left join c.souvenir s
            where c.event.id = :eventId
              and (
                  c.type = :totalType
                  or exists (
                      select cc.id
                      from CapacityCategory cc
                      where cc.capacity = c
                        and cc.eventCategory.id = :categoryId
                  )
              )
            order by c.id
            """)
    List<CapacityTarget> findRegistrationTargets(
            @Param("eventId") String eventId,
            @Param("categoryId") String categoryId,
            @Param("totalType") CapacityType totalType
    );

    /**
     * 지급 대상 기념품들의 재고 설정 후보를 조회한다.
     *
     * 서비스에서 실제 선택 사이즈와 빈 문자열의 전체 재고를 추린다.
     * souvenirIds가 비어 있으면 이 메서드를 호출하지 않는다.
     */
    @Query("""
            select new kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityTarget(
                c.id, c.type, s.id, c.size
            )
            from Capacity c
            join c.souvenir s
            where c.event.id = :eventId
              and s.id in :souvenirIds
            order by c.id
            """)
    List<CapacityTarget> findSouvenirTargets(
            @Param("eventId") String eventId,
            @Param("souvenirIds") Collection<String> souvenirIds
    );

    /**
     * 신규 확보가 허용되고 잔여 수량이 충분한 경우 홀딩 수량을 증가시킨다.
     *
     * 양수 수량만 허용하며, 검증과 증가를 하나의 UPDATE로 수행한다.
     *
     * @return 확보 성공 시 1, 조건 불충족 또는 대상 부재 시 0
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Capacity c
               set c.heldCount = c.heldCount + :quantity,
                   c.updatedAt = :now
             where c.id = :capacityId
               and c.event.id = :eventId
               and c.active = true
               and :quantity > 0
               and c.heldCount + c.confirmedCount + :quantity <= c.limitCount
            """)
    int acquireHeld(
            @Param("eventId") String eventId,
            @Param("capacityId") String capacityId,
            @Param("quantity") int quantity,
            @Param("now") LocalDateTime now
    );

    /**
     * 기존 홀딩 수량을 확정 수량으로 이동한다.
     *
     * 전체 점유 수량은 변하지 않으며 active 여부와 무관하게 처리한다.
     * 해당 예약의 확정 가능 상태와 중복 처리는 서비스에서 검증한다.
     *
     * @return 이동 성공 시 1, 홀딩 부족 또는 대상 부재 시 0
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Capacity c
               set c.heldCount = c.heldCount - :quantity,
                   c.confirmedCount = c.confirmedCount + :quantity,
                   c.updatedAt = :now
             where c.id = :capacityId
               and c.event.id = :eventId
               and :quantity > 0
               and c.heldCount >= :quantity
            """)
    int confirmHeld(
            @Param("eventId") String eventId,
            @Param("capacityId") String capacityId,
            @Param("quantity") int quantity,
            @Param("now") LocalDateTime now
    );

    /**
     * 기존 홀딩 수량을 반환한다.
     *
     * active 여부와 무관하게 처리한다.
     * 해당 예약의 반환 가능 상태와 중복 처리는 서비스에서 검증한다.
     *
     * @return 반환 성공 시 1, 홀딩 부족 또는 대상 부재 시 0
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Capacity c
               set c.heldCount = c.heldCount - :quantity,
                   c.updatedAt = :now
             where c.id = :capacityId
               and c.event.id = :eventId
               and :quantity > 0
               and c.heldCount >= :quantity
            """)
    int releaseHeld(
            @Param("eventId") String eventId,
            @Param("capacityId") String capacityId,
            @Param("quantity") int quantity,
            @Param("now") LocalDateTime now
    );


    /**
     * 전체 정원 중 임시 확보와 확정 수량의 합이 한도에 도달한 행을 센다.
     *
     * 확보 UPDATE 이후 DB의 수량으로 판단하며,
     * 영속성 컨텍스트에 남은 Capacity 엔티티의 카운터는 사용하지 않는다.
     *
     * 서비스에서는 totalType에 EVENT_TOTAL을 전달한다.
     */
    @Query("""
            select count(c)
            from Capacity c
            where c.event.id = :eventId
              and c.type = :totalType
              and c.heldCount + c.confirmedCount >= c.limitCount
            """)
    long countFullTotalCapacities(
            @Param("eventId") String eventId,
            @Param("totalType") CapacityType totalType
    );
}