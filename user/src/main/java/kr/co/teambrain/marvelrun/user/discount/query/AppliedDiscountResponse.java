package kr.co.teambrain.marvelrun.user.discount.query;

import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import java.math.BigDecimal;

/** 프런트가 재계산하지 않고 표시할 실제 할인 단계다. */
public record AppliedDiscountResponse(String policyId, long revision, DiscountModels.Stage stage,
        DiscountModels.Method method, BigDecimal ratePercent, BigDecimal fixedAmount,
        BigDecimal amountBefore, BigDecimal amountAfter, BigDecimal reductionAmount) {
    /** 계산 도메인 결과를 명시적인 외부 응답으로 변환한다. */
    public static AppliedDiscountResponse from(DiscountModels.AppliedDiscount result) {
        return new AppliedDiscountResponse(result.policyId(), result.revision(), result.stage(), result.method(),
                result.ratePercent(), result.fixedAmount(), result.amountBefore(), result.amountAfter(), result.reductionAmount());
    }
}
