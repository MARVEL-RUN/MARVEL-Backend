package kr.co.teambrain.marvelrun.admin.discount.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import kr.co.teambrain.marvelrun.common.entity.DiscountPolicyCategoryBase;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** admin 모듈에서 할인 구성의 DiscountPolicyCategory 행을 읽는다. */
@Entity
@Getter
@SuperBuilder
@NoArgsConstructor
@Table(name = "discount_policy_category", uniqueConstraints = @UniqueConstraint(name = "uk_discount_policy_category", columnNames = {"policy_id", "event_category_id"}))
public class DiscountPolicyCategory extends DiscountPolicyCategoryBase {
}