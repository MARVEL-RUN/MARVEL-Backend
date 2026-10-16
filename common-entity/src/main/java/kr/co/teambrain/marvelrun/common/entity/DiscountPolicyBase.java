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

/** 대회별 할인 값과 적용 범위를 저장한다. 내부/외부 할인은 stage로 구분한다. */
@Getter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
public abstract class DiscountPolicyBase {
    // 정책 식별자는 계산 근거에 남기며 설정 변경 시에도 유지한다.
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, length = 40)
    protected String id;

    // 정책과 대상 종목·링크 필터는 동일 대회에 속해야 한다.
    @Column(name = "event_id", nullable = false, length = 40)
    protected String eventId;

    @Column(nullable = false, length = 100)
    protected String name;

    // 할인 단계와 계산 방식을 구분하고 방식에 해당하는 값만 사용한다.
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

    // 개인·단체 및 종목별 적용 범위를 정한다.
    @Enumerated(EnumType.STRING)
    @Column(name = "application_type", nullable = false, length = 20)
    protected DiscountModels.ApplicationType applicationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "category_scope", nullable = false, length = 20)
    protected DiscountModels.CategoryScope categoryScope;

    // 최소 인원은 단체 구성 검증에 사용하며 NULL이면 인원 제한이 없다.
    @Column(name = "minimum_group_size")
    protected Integer minimumGroupSize;

    // 운영 설정 변경 시 수동으로 올리는 개정번호이며 JPA 낙관적 잠금 값이 아니다.
    @Builder.Default
    @Column(nullable = false)
    protected long revision = 1;

    // 설정 생성·갱신 시각은 신청별 계산 기록 시각과 구분한다.
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    protected LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    protected LocalDateTime updatedAt;
}
