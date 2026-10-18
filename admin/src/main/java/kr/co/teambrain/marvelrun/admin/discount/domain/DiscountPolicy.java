package kr.co.teambrain.marvelrun.admin.discount.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import kr.co.teambrain.marvelrun.common.entity.DiscountPolicyBase;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** 대회별 할인 값과 적용 범위를 조회하는 정책 엔티티다. */
@Entity
@Getter
@SuperBuilder
@NoArgsConstructor
@Table(name = "discount_policy")
public class DiscountPolicy extends DiscountPolicyBase {
}
