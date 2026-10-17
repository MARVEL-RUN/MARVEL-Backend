package kr.co.teambrain.marvelrun.user.event.query.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import kr.co.teambrain.marvelrun.user.discount.domain.RegistrationPriceCalculation;
import kr.co.teambrain.marvelrun.user.discount.query.AppliedDiscountResponse;

/** 마지막으로 반영한 참가비 계산 근거를 제공하며 실제 납부·환불액과 구분한다. */
@Schema(description = "저장된 마지막 참가비 계산 근거. 실제 납부액이 아니며 전체 취소 후에도 보존됩니다.")
public record RegistrationPriceCalculationResponse(
        BigDecimal baseAmount,
        BigDecimal finalAmount,
        BigDecimal roundingAdjustmentAmount,
        List<AppliedDiscountResponse> appliedDiscounts
) {
    /** 현재 정책을 재조회하거나 재계산하지 않고 당시 적용 순서와 금액을 반환한다. */
    public static RegistrationPriceCalculationResponse from(RegistrationPriceCalculation calculation) {
        // 기록에 저장한 할인 목록을 명시적인 외부 응답으로 변환한다.
        return new RegistrationPriceCalculationResponse(
                calculation.getBaseAmount(), calculation.getFinalAmount(), calculation.getRoundingAdjustmentAmount(),
                calculation.getSnapshot().appliedDiscounts().stream().map(AppliedDiscountResponse::from).toList());
    }
}
