package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;


/**
 * 정원 제한이 적용되는 종목을 연결한다.
 *
 * 단일 종목 정원과 어린이 종목 정원은 대상 종목 하나를 연결하고,
 * 합산 정원은 동일한 Capacity에 여러 종목을 연결한다.
 *
 * 신청 시 필요한 Capacity를 찾기 위한 설정 데이터이며,
 * 개별 신청의 실제 확보 내역은 ReservationItem에 저장한다.
 *
 * @param <C> 정원을 관리하는 Capacity 엔티티 타입
 * @param <EC> 제한이 적용되는 종목 엔티티 타입
 */
@Getter
@MappedSuperclass
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class CapacityCategoryBase<
        C extends CapacityBase<?, ?>,
        EC extends EventCategoryBase<?>> {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    protected String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "capacity_id", nullable = false)
    protected C capacity;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_category_id", nullable = false)
    protected EC eventCategory;
}