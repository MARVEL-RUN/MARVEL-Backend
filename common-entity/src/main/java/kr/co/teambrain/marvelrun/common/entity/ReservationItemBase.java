package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;


/**
 * 개별 예약이 확보한 Capacity와 수량을 정의한다.
 *
 * 동일 예약 안에서는 하나의 Capacity에 대해 하나의 행을 유지한다.
 * 결제 확정이나 자원 반환 시 이 기록을 기준으로 수량을 처리하여,
 * 현재 종목·기념품 설정에서 확보 대상을 다시 계산하지 않도록 한다.
 *
 * 실제 수량 반영 여부는 상위 Reservation의 상태로 판단한다.
 * 이 테이블은 모든 변경 이력을 누적하는 로그가 아니다.
 *
 * @param <R> 확보 내역을 소유하는 Reservation 엔티티 타입
 * @param <C> 확보 대상 Capacity 엔티티 타입
 */
@Getter
@MappedSuperclass
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class ReservationItemBase<
        R extends ReservationBase<?>,
        C extends CapacityBase<?, ?>> {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    protected String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    protected R reservation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "capacity_id", nullable = false)
    protected C capacity;

    @Column(name = "quantity", nullable = false)
    protected int quantity;
}