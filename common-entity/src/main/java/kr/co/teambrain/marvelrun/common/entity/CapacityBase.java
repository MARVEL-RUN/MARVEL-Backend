package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.CapacityType;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 대회별 정원 또는 기념품 재고의 제한 수량과 사용 수량을 정의한다.
 *
 * 한 행은 전체 정원, 종목 정원, 어린이 정원, 합산 정원,
 * 기념품 재고 중 하나의 독립적인 수량 제한을 나타낸다.
 *
 * heldCount는 임시 확보 수량, confirmedCount는 확정 수량이며,
 * 두 수량의 합은 limitCount를 초과할 수 없다.
 * 수량 변경은 Repository의 조건부 원자적 UPDATE로 처리한다.
 *
 * active가 false이면 신규 확보만 차단하며,
 * 기존 확보분의 확정과 반환은 허용한다.
 *
 * @param <E> 소속 대회 엔티티 타입
 * @param <S> 재고 관리 대상 기념품 엔티티 타입
 */
@Getter
@MappedSuperclass
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class CapacityBase<
        E extends EventBase,
        S extends SouvenirBase<?>> {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    protected String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    protected E event;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    protected CapacityType type;

    @Column(name = "resource_key", nullable = false, length = 100)
    protected String resourceKey;

    @Column(name = "name", nullable = false, length = 100)
    protected String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "souvenir_id")
    protected S souvenir;

    // 빈 문자열: 사이즈 구분 없음.
    // "150", "XL" 등: 해당 단일 사이즈의 재고.
    @Column(name = "size", nullable = false, length = 100)
    protected String size = "";

    /**
     * 이 자원의 최대 점유 수량.
     *
     * EVENT_TOTAL이면 해당 대회의 최대 참가 인원이며,
     * 다른 유형이면 해당 종목·복합 정원·기념품의 한도이다.
     *
     * heldCount + confirmedCount는 이 값을 초과할 수 없다.
     */
    @Column(name = "limit_count", nullable = false)
    protected int limitCount;

    @Column(name = "held_count", nullable = false)
    protected int heldCount;

    @Column(name = "confirmed_count", nullable = false)
    protected int confirmedCount;

    // false는 신규 확보만 차단한다.
    @Column(name = "active", nullable = false)
    protected boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    protected LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    protected LocalDateTime updatedAt;
}