package kr.co.teambrain.marvelrun.user.capacity.command.repository;

import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.ReservationItem;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.ReservationAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * 예약 상세 저장과 기존 확보 내역 조회를 담당한다.
 *
 * 결제 확정과 반환 시 신청의 현재 종목 설정을 다시 계산하지 않고,
 * 이 Repository가 반환한 확보 내역을 사용한다.
 */
@Transactional(propagation = Propagation.MANDATORY)
public interface ReservationItemCommandRepository
        extends JpaRepository<ReservationItem, String> {

    /**
     * 지정한 예약들의 확보 내역을 Capacity 식별자 순서로 조회한다.
     *
     * 서비스에서 동일 Capacity의 수량을 합산한 뒤,
     * Capacity 식별자 순서로 수량 UPDATE를 수행한다.
     * reservationIds가 비어 있으면 호출하지 않는다.
     */
    @Query("""
            select new kr.co.teambrain.marvelrun.user.capacity.command.application.dto.ReservationAllocation(
                ri.reservation.id, ri.capacity.id, ri.quantity
            )
            from ReservationItem ri
            where ri.reservation.id in :reservationIds
            order by ri.capacity.id, ri.reservation.id
            """)
    List<ReservationAllocation> findAllocations(
            @Param("reservationIds") Collection<String> reservationIds
    );
}