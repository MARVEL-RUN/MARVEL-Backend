package kr.co.teambrain.marvelrun.common.discount;

import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** 할인 순서·원 단위 경계·기존 어린이 생일 판정을 검증한다. */
class DiscountPriceCalculatorTest {
    private final DiscountPriceCalculator calculator = new DiscountPriceCalculator();

    /** 어린이 참가비를 먼저 확정한 뒤 링크 할인율을 적용한다. */
    @Test
    void internalFixedPricePrecedesExternalRate() {
        DiscountModels.PriceResult result = calculator.calculateDiscountedPrice(new BigDecimal("70000"), child(), rate("50"));
        assertEquals(new BigDecimal("20000"), result.finalAmount());
        assertEquals(2, result.appliedDiscounts().size());
        assertEquals(new BigDecimal("30000"), result.appliedDiscounts().getFirst().reductionAmount());
    }

    /** 참가자별 절사 후 합산해야 하며 절사 전 가격을 합산하지 않는다. */
    @Test
    void roundsPerParticipantAndRejectsZero() {
        DiscountModels.PriceResult price = calculator.calculateDiscountedPrice(new BigDecimal("101"), null, rate("50"));
        assertEquals(new BigDecimal("50"), price.finalAmount());
        assertEquals(0, price.roundingAdjustmentAmount().compareTo(new BigDecimal("0.5")));
        assertEquals(DiscountModels.Failure.ZERO_AMOUNT,
                calculator.calculateDiscountedPrice(BigDecimal.ONE, null, rate("50")).failure());
    }

    /** 100%와 저장 정밀도를 초과하는 비율은 설정 오류다. */
    @Test
    void rejectsInvalidRates() {
        assertEquals(DiscountModels.Failure.CONFIGURATION,
                calculator.calculateDiscountedPrice(new BigDecimal("70000"), null, rate("100")).failure());
        assertEquals(DiscountModels.Failure.CONFIGURATION,
                calculator.calculateDiscountedPrice(new BigDecimal("70000"), null, rate("10.001")).failure());
    }

    /** 고정 참가비는 기본가와 min을 취하지 않는 기존 기준을 보존한다. */
    @Test
    void fixedPriceCanIncreaseAmount() {
        DiscountModels.PriceResult result = calculator.calculateDiscountedPrice(new BigDecimal("30000"), child(), null);
        assertEquals(new BigDecimal("40000"), result.finalAmount());
        assertEquals(new BigDecimal("-10000"), result.appliedDiscounts().getFirst().reductionAmount());
    }

    /** 윤년 생일도 기존 birth.plusYears 상한 판정과 같아야 한다. */
    @Test
    void leapBirthdayUsesExistingBoundary() {
        LocalDate birth = LocalDate.of(2012, 2, 29);
        assertTrue(child().matchesAge(birth, LocalDate.of(2025, 2, 27)));
        assertFalse(child().matchesAge(birth, LocalDate.of(2025, 2, 28)));
    }

    private DiscountModels.Policy child() {
        return new DiscountModels.Policy("child", "event", 1, DiscountModels.Stage.INTERNAL, DiscountModels.Method.FIXED_PRICE,
                null, new BigDecimal("40000"), DiscountModels.ApplicationType.BOTH, DiscountModels.CategoryScope.ALL, Set.of(), null, 0, 13);
    }
    private DiscountModels.Policy rate(String value) {
        return new DiscountModels.Policy("link", "event", 2, DiscountModels.Stage.EXTERNAL, DiscountModels.Method.PERCENT,
                new BigDecimal(value), null, DiscountModels.ApplicationType.ORGANIZATION, DiscountModels.CategoryScope.ALL, Set.of(), 5, null, null);
    }
}
