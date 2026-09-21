package kr.co.teambrain.marvelrun.common.entity;


import jakarta.persistence.*;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.json_object.ReservationHistoryEntry;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;


/**
 * 개별 신청의 자원 확보 상태를 정의한다.
 *
 * 신청 한 건당 하나의 Reservation을 유지하며,
 * 확보한 Capacity와 수량은 ReservationItem에 저장한다.
 *
 * HELD와 PROCESSING 상태의 수량은 Capacity의 heldCount에,
 * CONSUMED 상태의 수량은 confirmedCount에 반영한다.
 * RELEASED 상태에서는 해당 예약이 점유하는 수량이 없다.
 *
 * version은 동시 상태 변경을 감지하기 위한 낙관적 잠금 값이다.
 * 상태 변경과 수량 변경은 동일 트랜잭션에서 처리해야 한다.
 *
 * 이번 MVP에서는 expiresAt을 null로 유지하며 자동 만료하지 않는다.
 *
 * @param <R> 자원을 확보하는 신청 엔티티 타입
 */
@Getter
@MappedSuperclass
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class ReservationBase<
        R extends RegistrationBase<?, ?, ?, ?, ?>> {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, length = 40)
    protected String id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "registration_id", nullable = false)
    protected R registration;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    protected ReservationStatus status = ReservationStatus.HELD;

    // 이번 MVP에서는 NULL 유지. 자동 만료 처리 없음.
    @Column(name = "expires_at")
    protected LocalDateTime expiresAt;

    @Version
    @Column(name = "version", nullable = false)
    protected Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    protected LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    protected LocalDateTime updatedAt;

    /**
     * 현재 확보 회차.
     * registration - reservation이 1:1 관계임에 따라
     * 로그 누적형으로 이력을 관리해야하므로 구성된 별도의 버전처리.
     *
     * 최초 확보는 1이며 반환 후 재확보할 때 한정으로 증가한다.
     * 동시성 검증용 version과는 별개이다.
     */
    @Builder.Default
    @Column(name = "hold_sequence", nullable = false)
    protected int holdSequence = 1;

    /**
     * 예약 동작을 발생 순서대로 누적한 JSON 배열이다.
     *
     * 기존 항목을 수정하거나 삭제하지 않고 새 항목을 추가한다.
     * 상태 및 수량 변경과 동일 트랜잭션에서 저장한다.
     */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "history", nullable = false, columnDefinition = "JSON")
    protected List<ReservationHistoryEntry> history = new ArrayList<>();
}