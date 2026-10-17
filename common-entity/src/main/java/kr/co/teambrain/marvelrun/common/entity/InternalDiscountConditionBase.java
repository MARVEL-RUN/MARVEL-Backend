package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** 서비스 내부 할인 조건을 정책 ID로 연결한다. 현재 지원 조건은 대회일 기준 연령 범위다. */
@Getter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
public abstract class InternalDiscountConditionBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, length = 40)
    protected String id;

    // 정책당 조건 한 행을 사용하며 정책과의 JPA 연관관계는 만들지 않는다.
    @Column(name = "policy_id", nullable = false, length = 40, unique = true)
    protected String policyId;

    // 하한은 포함하고 상한은 제외한다. 미래 조건을 미리 범용화하지 않는다.
    @Column(name = "min_age_inclusive", nullable = false)
    protected int minAgeInclusive;

    @Column(name = "max_age_exclusive", nullable = false)
    protected int maxAgeExclusive;
}
