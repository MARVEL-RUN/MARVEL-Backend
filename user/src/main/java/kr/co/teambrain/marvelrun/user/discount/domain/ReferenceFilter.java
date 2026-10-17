package kr.co.teambrain.marvelrun.user.discount.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import kr.co.teambrain.marvelrun.common.entity.ReferenceFilterBase;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** 할인 자격으로 부여한 신청 분류 코드와 관리자 표시 설정을 조회한다. */
@Entity
@Getter
@SuperBuilder
@NoArgsConstructor
@Table(name = "reference_filter",
    uniqueConstraints = @UniqueConstraint(name = "uk_reference_filter_event_code",
    columnNames = {
    "event_id",
    "code"
}))
public class ReferenceFilter extends ReferenceFilterBase {
}
