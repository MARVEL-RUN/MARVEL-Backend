package kr.co.teambrain.marvelrun.common.discount.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** 영속 엔티티와 독립적인 할인 정책 및 계산 결과를 전달한다. */
public final class DiscountModels {
    private DiscountModels() { }

    public enum Stage { INTERNAL, EXTERNAL }
    public enum Method { FIXED_PRICE, PERCENT }
    public enum ApplicationType { PERSONAL, ORGANIZATION, BOTH }
    public enum CategoryScope { ALL, SELECTED }
    public enum Failure { NONE, CONFIGURATION, ZERO_AMOUNT }

    /** 한 번의 계산에서 사용하는 정책 값과 대상 조건이다. */
    public record Policy(String id, String eventId, long revision, Stage stage, Method method,
                         BigDecimal ratePercent, BigDecimal fixedAmount, ApplicationType applicationType,
                         CategoryScope categoryScope, Set<String> categoryIds, Integer minimumGroupSize,
                         Integer minAgeInclusive, Integer maxAgeExclusive) {
        /** 현재 종목과 신청 유형이 가격 적용 대상인지 판정한다. */
        public boolean targets(String categoryId, boolean organization) {
            return (applicationType == ApplicationType.BOTH
                    || applicationType == (organization ? ApplicationType.ORGANIZATION : ApplicationType.PERSONAL))
                    && (categoryScope == CategoryScope.ALL || categoryIds.contains(categoryId));
        }

        /** 생일 당일 경계 및 윤년 생일을 기존 plusYears 기준으로 판정한다. */
        public boolean matchesAge(LocalDate birth, LocalDate eventDate) {
            return minAgeInclusive != null && maxAgeExclusive != null
                    && !eventDate.isBefore(birth.plusYears(minAgeInclusive))
                    && eventDate.isBefore(birth.plusYears(maxAgeExclusive));
        }
    }

    /** 한 할인 단계의 실제 적용 금액이며 감액은 음수일 수도 있다. */
    public record AppliedDiscount(String policyId, long revision, Stage stage, Method method,
                                  BigDecimal ratePercent, BigDecimal fixedAmount,
                                  BigDecimal amountBefore, BigDecimal amountAfter,
                                  BigDecimal reductionAmount) { }

    /** 실패 상태는 모듈별 CustomException으로 변환한다. */
    public record PriceResult(Failure failure, BigDecimal finalAmount, BigDecimal roundingAdjustmentAmount,
                              List<AppliedDiscount> appliedDiscounts) { }
}
