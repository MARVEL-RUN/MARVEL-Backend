package kr.co.teambrain.marvelrun.common.discount;

import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** 내부 고정 참가비 이후 외적 정률을 적용하며 참가자별 원 미만을 절사한다. */
public final class DiscountPriceCalculator {
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");

    /** 판정이 완료된 단계별 정책을 적용하고 금액과 실제 적용 개정을 반환한다. */
    public DiscountModels.PriceResult calculateDiscountedPrice(
        BigDecimal baseAmount, DiscountModels.Policy internal, DiscountModels.Policy external) {
        // 잘못된 설정으로 일부 할인만 적용하지 않는다.
        if (baseAmount == null || baseAmount.signum() < 0 || baseAmount.compareTo(MAX_AMOUNT) > 0
            || (internal != null && !validPolicy(internal, DiscountModels.Stage.INTERNAL))
            || (external != null && !validPolicy(external, DiscountModels.Stage.EXTERNAL))) {
            return failure(DiscountModels.Failure.CONFIGURATION);
        }

        // 내부 할인 다음에 외적 할인을 계산하고 마지막에만 절사한다.
        BigDecimal amount = baseAmount;
        List<DiscountModels.AppliedDiscount> applied = new ArrayList<>();
        if (internal != null) {
            BigDecimal after = internal.fixedAmount();
            applied.add(describe(internal, amount, after));
            amount = after;
        }
        if (external != null) {
            BigDecimal after = amount.multiply(HUNDRED.subtract(external.ratePercent())).movePointLeft(2);
            applied.add(describe(external, amount, after));
            amount = after;
        }
        BigDecimal rounded = amount.setScale(0, RoundingMode.DOWN);
        if (rounded.signum() == 0) {
            return failure(DiscountModels.Failure.ZERO_AMOUNT);
        }
        if (rounded.compareTo(MAX_AMOUNT) > 0) {
            return failure(DiscountModels.Failure.CONFIGURATION);
        }
        return new DiscountModels.PriceResult(DiscountModels.Failure.NONE, baseAmount, rounded,
            amount.subtract(rounded), List.copyOf(applied));
    }

    /** 이번에 지원하는 단계와 계산 방식 및 저장 범위를 검증한다. */
    private boolean validPolicy(DiscountModels.Policy policy, DiscountModels.Stage expected) {
        if (policy.stage() != expected || policy.revision() < 1) {
            return false;
        }
        if (expected == DiscountModels.Stage.INTERNAL) {
            return policy.method() == DiscountModels.Method.FIXED_PRICE && policy.ratePercent() == null
                && policy.fixedAmount() != null && policy.fixedAmount().signum() > 0
                && policy.fixedAmount().compareTo(MAX_AMOUNT) <= 0;
        }
        return policy.method() == DiscountModels.Method.PERCENT && policy.fixedAmount() == null
            && policy.ratePercent() != null && policy.ratePercent().signum() > 0
            && policy.ratePercent().compareTo(HUNDRED) < 0
            && policy.ratePercent().stripTrailingZeros().scale() <= 2;
    }

    /** 단계를 표시할 수 있도록 계산 입력과 결과를 함께 보존한다. */
    private DiscountModels.AppliedDiscount describe(DiscountModels.Policy policy, BigDecimal before, BigDecimal after) {
        return new DiscountModels.AppliedDiscount(policy.id(), policy.revision(), policy.stage(), policy.method(),
            policy.ratePercent(), policy.fixedAmount(), before, after, before.subtract(after));
    }

    /** 실패 시 가격으로 오인할 수 있는 부분 계산 결과를 반환하지 않는다. */
    private DiscountModels.PriceResult failure(DiscountModels.Failure failure) {
        return new DiscountModels.PriceResult(failure, null, null, null, List.of());
    }
}
