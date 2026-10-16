package kr.co.teambrain.marvelrun.user.discount.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.RegistrationPriceCalculationBase;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** user 서버가 저장한 참가비 계산 근거를 변경 없이 유지한다. */
@Entity
@Getter
@SuperBuilder
@NoArgsConstructor
@Table(name = "registration_price_calculation", indexes = {
    @Index(name = "idx_price_calculation_registration_created", columnList = "registration_id,created_at")
})
public class RegistrationPriceCalculation extends RegistrationPriceCalculationBase {
}
