package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import lombok.Builder;
import org.hibernate.annotations.UpdateTimestamp;
import java.math.BigDecimal;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;
/** 할인 구성의 DiscountPolicy 저장 필드를 ID 참조 방식으로 공유한다. */
@Getter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
public abstract class DiscountPolicyBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, length = 40)
    protected String id;

    @Column(name = "event_id", nullable = false, length = 40)
    protected String eventId;
    @Column(nullable = false, length = 100)
    protected String name;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    protected DiscountModels.Stage stage;
    @Enumerated(EnumType.STRING)
    @Column(name = "calculation_method", nullable = false, length = 20)
    protected DiscountModels.Method calculationMethod;
    @Column(name = "rate_percent", precision = 5, scale = 2)
    protected BigDecimal ratePercent;
    @Column(name = "fixed_amount", precision = 12, scale = 2)
    protected BigDecimal fixedAmount;
    @Enumerated(EnumType.STRING)
    @Column(name = "application_type", nullable = false, length = 20)
    protected DiscountModels.ApplicationType applicationType;
    @Enumerated(EnumType.STRING)
    @Column(name = "category_scope", nullable = false, length = 20)
    protected DiscountModels.CategoryScope categoryScope;
    @Column(name = "minimum_group_size")
    protected Integer minimumGroupSize;
    @Builder.Default
    @Column(nullable = false)
    protected long revision = 1;
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    protected LocalDateTime createdAt;
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    protected LocalDateTime updatedAt;
}