package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import kr.co.teambrain.marvelrun.common.discount.model.RegistrationPriceCalculationSnapshot;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 신청에 실제 반영한 계산 한 건을 보존한다. 결제 성공·실패 원장을 대체하지 않는다. */
@Getter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
public abstract class RegistrationPriceCalculationBase {
    // 소유 신청은 ID로만 연결하며 기존 계산 행을 갱신하지 않는다.
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(length = 40, nullable = false, updatable = false)
    protected String id;

    @Column(name = "registration_id", length = 40, nullable = false, updatable = false)
    protected String registrationId;

    // 최종금액과 절사 전 차액을 분리하여 소수 정밀도를 잃지 않는다.
    @Column(name = "base_amount", precision = 12, scale = 2, nullable = false, updatable = false)
    protected BigDecimal baseAmount;

    @Column(name = "final_amount", precision = 12, scale = 2, nullable = false, updatable = false)
    protected BigDecimal finalAmount;

    @Column(name = "rounding_adjustment_amount", precision = 12, scale = 6, nullable = false, updatable = false)
    protected BigDecimal roundingAdjustmentAmount;

    // 미래 정책 조회 없이 당시 적용값을 해석할 수 있도록 형식 버전을 포함한다.
    @Enumerated(EnumType.STRING)
    @Column(name = "calculation_reason", length = 30, nullable = false, updatable = false)
    protected RegistrationPriceCalculationSnapshot.Reason calculationReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", columnDefinition = "json", nullable = false, updatable = false)
    protected RegistrationPriceCalculationSnapshot snapshot;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    protected LocalDateTime createdAt;
}
