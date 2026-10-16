package kr.co.teambrain.marvelrun.admin.discount.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import kr.co.teambrain.marvelrun.common.entity.ReferenceFilterBase;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** admin 모듈에서 할인 구성의 ReferenceFilter 행을 읽는다. */
@Entity
@Getter
@SuperBuilder
@NoArgsConstructor
@Table(name = "reference_filter", uniqueConstraints = @UniqueConstraint(name = "uk_reference_filter_event_code", columnNames = {"event_id", "code"}))
public class ReferenceFilter extends ReferenceFilterBase {
}