package kr.co.teambrain.marvelrun.admin.payment.command;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import static org.assertj.core.api.Assertions.*;

/** 임시 차단의 나이 경계, 양방향 전환 및 금액/종목 한정 범위를 검증한다. */
class AdminAdjustmentTemporaryBlockTest {
    /** 대회 당일 13번째 생일이면 성인, 하루 뒤면 어린이로 분류한다. */
    @Test void blocksBothDirectionsAtThirteenthBirthday() {
        LocalDate date = LocalDate.of(2026,11,1);
        for (String[] births : new String[][] {{"2013-11-01","2013-11-02"},{"2013-11-02","2013-11-01"}}) {
            assertThatThrownBy(() -> AdminAdjustmentTemporaryBlock.validateBirthTransition(births[0],births[1],date))
                    .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode())
                            .isEqualTo(ErrorCode.ADMIN_ADJUSTMENT_AGE_GROUP_TEMPORARILY_BLOCKED));
        }
        assertThatCode(() -> AdminAdjustmentTemporaryBlock.validateBirthTransition("1990-01-01","1991-01-01",date)).doesNotThrowAnyException();
        assertThatCode(() -> AdminAdjustmentTemporaryBlock.validateBirthTransition("2015-01-01","2016-01-01",date)).doesNotThrowAnyException();
    }

    /** 다른 종목으로 변경하면서 계약금액이 커질 때만 차단한다. 동일 종목은 범위 밖이다. */
    @Test void blocksOnlyCategoryChangeWithHigherContract() {
        assertThatThrownBy(() -> AdminAdjustmentTemporaryBlock.validateCategoryPriceIncrease("a","b",money("70000"),money("80000")))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_ADJUSTMENT_CATEGORY_PRICE_INCREASE_TEMPORARILY_BLOCKED));
        assertThatCode(() -> AdminAdjustmentTemporaryBlock.validateCategoryPriceIncrease("a","b",money("70000"),money("70000.00"))).doesNotThrowAnyException();
        assertThatCode(() -> AdminAdjustmentTemporaryBlock.validateCategoryPriceIncrease("a","b",money("70000"),money("40000"))).doesNotThrowAnyException();
        assertThatCode(() -> AdminAdjustmentTemporaryBlock.validateCategoryPriceIncrease("a","a",money("70000"),money("80000"))).doesNotThrowAnyException();
        assertThatCode(() -> AdminAdjustmentTemporaryBlock.validateCategoryPriceIncrease("a","b",money("90000"),money("80000"))).doesNotThrowAnyException();
    }

    /** 금액 스케일 차이를 비교에서 배제한다. */
    private static BigDecimal money(String value) { return new BigDecimal(value); }
}
