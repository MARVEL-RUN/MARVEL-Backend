package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.*;
import kr.co.teambrain.marvelrun.common.inheritance_enum.upgrade.PackageUpgradeStatus;
import kr.co.teambrain.marvelrun.common.json_object.PackageUpgradeEntry;
import kr.co.teambrain.marvelrun.common.json_object.PackageUpgradeHeldCapacity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 단품 → 패키지 상향 대기.
 *
 * 요청 때 늘어나는 정원을 held로 잡고 신청의 계약금액을 패키지 가격으로 올린다.
 * 결제가 승인되면 종목·참가 항목·정원을 패키지로 바꾸고 COMPLETED가 된다.
 * active_flag는 DB 생성 컬럼이라 매핑하지 않는다. PENDING은 신청마다 하나뿐이다.
 *
 * @param <E> 대회 타입
 * @param <R> 신청 타입
 * @param <C> 종목 타입
 */
@Getter
@MappedSuperclass
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class PackageUpgradeBase<
        E extends EventBase,
        R extends RegistrationBase<?, ?, ?, ?, ?>,
        C extends EventCategoryBase<?>> {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    protected String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    protected E event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "registration_id", nullable = false)
    protected R registration;

    /** 상향할 패키지 상품. 단품의 parent_category_id다. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_category_id", nullable = false)
    protected C targetCategory;

    /** 상향 요청이 처음 만든 주문. 재결제로 주문이 바뀌어도 상향은 신청 id로 찾는다. */
    @Column(name = "payment_id", length = 40)
    protected String paymentId;

    /** 차액. 요청 때 계약금액에 더하고, 취소 때 뺀다. */
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    protected BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    protected PackageUpgradeStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "added_entries", nullable = false, columnDefinition = "JSON")
    protected List<PackageUpgradeEntry> addedEntries;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "held_capacities", nullable = false, columnDefinition = "JSON")
    protected List<PackageUpgradeHeldCapacity> heldCapacities;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    protected LocalDateTime createdAt;

    @Column(name = "completed_at")
    protected LocalDateTime completedAt;

    @Column(name = "canceled_at")
    protected LocalDateTime canceledAt;

    public boolean isPending() {
        return status == PackageUpgradeStatus.PENDING;
    }
}
