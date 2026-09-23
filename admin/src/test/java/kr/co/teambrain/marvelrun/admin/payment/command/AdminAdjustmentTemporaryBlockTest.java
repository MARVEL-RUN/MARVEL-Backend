package kr.co.teambrain.marvelrun.admin.payment.command;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 테스트 서버 검증 재개 시 두 임시 차단이 해제되어 있는지 확인한다. 실제 업무 검증은 서비스 테스트가 담당한다. */
class AdminAdjustmentTemporaryBlockTest {
    /** 어린이↔성인 양방향 전환은 임시 가드에서 거절하지 않는다. */
    @Test void allowsBothAgeGroupTransitions() {
        LocalDate date = LocalDate.of(2026, 11, 1);
        assertThatCode(() -> AdminAdjustmentTemporaryBlock.validateBirthTransition(
                "2013-11-01", "2013-11-02", date)).doesNotThrowAnyException();
        assertThatCode(() -> AdminAdjustmentTemporaryBlock.validateBirthTransition(
                "2013-11-02", "2013-11-01", date)).doesNotThrowAnyException();
    }

    /** 다른 종목으로의 가격 상승도 임시 가드에서 거절하지 않는다. */
    @Test void allowsCategoryPriceIncrease() {
        assertThatCode(() -> AdminAdjustmentTemporaryBlock.validateCategoryPriceIncrease(
                "a", "b", new BigDecimal("70000"), new BigDecimal("80000"))).doesNotThrowAnyException();
    }
}
