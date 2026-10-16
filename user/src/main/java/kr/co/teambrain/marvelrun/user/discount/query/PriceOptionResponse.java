package kr.co.teambrain.marvelrun.user.discount.query;

import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import java.math.BigDecimal;
import java.util.List;

/** 조건 충족을 가정한 안내 금액이며 실제 참가자의 자격 확정 결과가 아니다. */
public record PriceOptionResponse(
        String scenario,
        String description,
        DiscountModels.ApplicationType applicationType,
        BigDecimal baseAmount,
        BigDecimal finalAmount,
        BigDecimal roundingAdjustmentAmount,
        List<AppliedDiscountResponse> appliedDiscounts
) {
}
