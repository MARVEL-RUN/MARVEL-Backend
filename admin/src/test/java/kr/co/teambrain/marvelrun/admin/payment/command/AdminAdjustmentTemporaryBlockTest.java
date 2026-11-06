package kr.co.teambrain.marvelrun.admin.payment.command;

import java.math.BigDecimal;
import java.time.LocalDate;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ProductType;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    /** 상품 종목으로 바꾸는 조정은 막는다. 기존 종목끼리는 그대로 둔다. */
    @Test void blocksProductCategoryChange() {
        EventCategory single = category("single", ProductType.SINGLE);
        EventCategory legacy = category("legacy", null);
        EventCategory otherLegacy = category("other", null);
        assertThatThrownBy(() -> AdminAdjustmentTemporaryBlock.validateProductCategoryChange(legacy, single))
                .isInstanceOfSatisfying(CustomException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.ADMIN_ADJUSTMENT_PRODUCT_CATEGORY_TEMPORARILY_BLOCKED));
        assertThatCode(() -> AdminAdjustmentTemporaryBlock.validateProductCategoryChange(legacy, otherLegacy))
                .doesNotThrowAnyException();
        assertThatCode(() -> AdminAdjustmentTemporaryBlock.validateProductCategoryChange(legacy, legacy))
                .doesNotThrowAnyException();
    }

    /**
     * 상품 신청은 조정을 항상 막는다. 신청 행 종목은 대표 상품일 뿐이다.
     * 임시 스위치(BLOCK_PRODUCT_CATEGORY_CHANGE)와 분리된 검사라 스위치를 꺼도 막혀야 한다.
     */
    @Test void blocksAnyAdjustmentOfProductRegistration() {
        for (EventCategory current : java.util.List.of(category("single", ProductType.SINGLE),
                category("package", ProductType.PACKAGE))) {
            assertThatThrownBy(() -> AdminAdjustmentTemporaryBlock.validateProductRegistration(current))
                    .isInstanceOfSatisfying(CustomException.class, exception -> assertThat(exception.getErrorCode())
                            .isEqualTo(ErrorCode.ADMIN_ADJUSTMENT_PRODUCT_REGISTRATION_BLOCKED));
        }
        assertThatCode(() -> AdminAdjustmentTemporaryBlock.validateProductRegistration(category("legacy", null)))
                .doesNotThrowAnyException();
    }

    private static EventCategory category(String id, ProductType productType) {
        EventCategory category = mock(EventCategory.class);
        when(category.getId()).thenReturn(id);
        when(category.getProductType()).thenReturn(productType);
        return category;
    }
}
