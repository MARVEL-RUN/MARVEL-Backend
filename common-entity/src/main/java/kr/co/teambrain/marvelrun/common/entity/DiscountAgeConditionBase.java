package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
/** 할인 구성의 DiscountAgeCondition 저장 필드를 ID 참조 방식으로 공유한다. */
@Getter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
public abstract class DiscountAgeConditionBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, length = 40)
    protected String id;

    @Column(name = "policy_id", nullable = false, length = 40, unique = true)
    protected String policyId;
    @Column(name = "min_age_inclusive", nullable = false)
    protected int minAgeInclusive;
    @Column(name = "max_age_exclusive", nullable = false)
    protected int maxAgeExclusive;
}