package kr.co.teambrain.marvelrun.user.discount.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import kr.co.teambrain.marvelrun.common.entity.InternalDiscountConditionBase;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** 서비스 내부 할인 정책에 연결된 현재 연령 조건을 조회한다. */
@Entity
@Getter
@SuperBuilder
@NoArgsConstructor
@Table(name = "internal_discount_condition")
public class InternalDiscountCondition extends InternalDiscountConditionBase {
}
