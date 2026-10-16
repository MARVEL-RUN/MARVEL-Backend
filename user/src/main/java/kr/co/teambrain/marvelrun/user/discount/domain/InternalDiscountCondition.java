package kr.co.teambrain.marvelrun.user.discount.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import kr.co.teambrain.marvelrun.common.entity.InternalDiscountConditionBase;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** user 모듈에서 할인 구성의 InternalDiscountCondition 행을 읽는다. */
@Entity
@Getter
@SuperBuilder
@NoArgsConstructor
@Table(name = "internal_discount_condition")
public class InternalDiscountCondition extends InternalDiscountConditionBase {
}