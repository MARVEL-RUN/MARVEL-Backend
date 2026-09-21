package kr.co.teambrain.marvelrun.user.capacity.command.repository;

import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 신청별 예약 조회와 저장을 담당한다.
 *
 * 조회한 엔티티의 상태를 변경하면 @Version으로 동시 변경을 감지한다.
 * 상태와 Capacity 수량은 서비스의 동일 트랜잭션에서 변경해야 한다.
 */
@Transactional(propagation = Propagation.MANDATORY)
public interface ReservationCommandRepository
        extends JpaRepository<Reservation, String> {

    /**
     * 개인 신청에 연결된 예약을 조회한다.
     *
     * 존재 여부만 조회하는 것이므로 이 호출 자체로 상태를 점유하지 않는다.
     */
    Optional<Reservation> findByRegistration_Id(String registrationId);

    /**
     * 여러 신청에 연결된 예약을 식별자 순서로 조회한다.
     *
     * 단체 처리에서 사용하며, 누락된 예약이 있는지는 서비스에서 검증한다.
     * registrationIds가 비어 있으면 호출하지 않는다.
     */
    @Query("""
            select r
            from Reservation r
            where r.registration.id in :registrationIds
            order by r.id
            """)
    List<Reservation> findAllByRegistrationIds(
            @Param("registrationIds") Collection<String> registrationIds
    );
}