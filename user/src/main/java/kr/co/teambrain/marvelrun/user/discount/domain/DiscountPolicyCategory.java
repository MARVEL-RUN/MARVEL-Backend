package kr.co.teambrain.marvelrun.user.discount.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import kr.co.teambrain.marvelrun.common.entity.DiscountPolicyCategoryBase;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** 선택 종목 정책과 대상 종목 사이의 연결을 조회한다. */
@Entity
@Getter
@SuperBuilder
@NoArgsConstructor
@Table(name = "discount_policy_category",
    uniqueConstraints = @UniqueConstraint(name = "uk_discount_policy_category",
    columnNames = {
    "policy_id",
    "event_category_id"
}))
public class DiscountPolicyCategory extends DiscountPolicyCategoryBase {
}
