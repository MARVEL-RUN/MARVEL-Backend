package kr.co.teambrain.marvelrun.user.discount.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import kr.co.teambrain.marvelrun.common.entity.DiscountAgeConditionBase;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** user 모듈에서 할인 구성의 DiscountAgeCondition 행을 읽는다. */
@Entity
@Getter
@SuperBuilder
@NoArgsConstructor
@Table(name = "discount_age_condition")
public class DiscountAgeCondition extends DiscountAgeConditionBase {
}