package kr.co.teambrain.marvelrun.admin.discount.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import kr.co.teambrain.marvelrun.common.entity.DiscountLinkBase;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** 신청의 영구 자격에 연결되는 공개 링크와 신규 취득 기간을 조회한다. */
@Entity
@Getter
@SuperBuilder
@NoArgsConstructor
@Table(name = "discount_link")
public class DiscountLink extends DiscountLinkBase {
}
