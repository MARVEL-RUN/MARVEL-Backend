package kr.co.teambrain.marvelrun.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
/** 할인 구성의 DiscountPolicyCategory 저장 필드를 ID 참조 방식으로 공유한다. */
@Getter
@SuperBuilder
@NoArgsConstructor
@MappedSuperclass
public abstract class DiscountPolicyCategoryBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, length = 40)
    protected String id;

    @Column(name = "policy_id", nullable = false, length = 40)
    protected String policyId;
    @Column(name = "event_category_id", nullable = false, length = 40)
    protected String eventCategoryId;
}