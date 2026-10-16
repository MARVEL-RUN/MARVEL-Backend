package kr.co.teambrain.marvelrun.user.discount;

import kr.co.teambrain.marvelrun.common.discount.DiscountPriceCalculator;
import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

/** 자격·필터와 현재 가격 적용 개정의 수명 차이를 검증한다. DB 실행 테스트는 아니다. */
class DiscountQualificationPersistenceTest {
    /** 비대상 종목 전환·복귀와 취소 후에도 원본 자격 연결은 유지한다. */
    @Test
    void qualificationSurvivesNonTargetAndCancellation() {
        Registration registration = Registration.builder().contractAmount(new BigDecimal("35000"))
                .paidAmount(new BigDecimal("35000")).status(RegistrationStatus.CONFIRMED).build();
        registration.assignDiscountQualification("link", "filter");
        DiscountPriceCalculator calculator = new DiscountPriceCalculator();
        registration.recordAppliedDiscountPrice(calculator.calculateDiscountedPrice(new BigDecimal("70000"), null, null));
        assertThat(registration.getDiscountLinkId()).isEqualTo("link");
        assertThat(registration.getReferenceFilterId()).isEqualTo("filter");
        assertThat(registration.getLinkPricePolicyRevision()).isNull();
        DiscountModels.Policy updated = new DiscountModels.Policy("policy", "event", 2,
                DiscountModels.Stage.EXTERNAL, DiscountModels.Method.PERCENT, new BigDecimal("50"), null,
                DiscountModels.ApplicationType.BOTH, DiscountModels.CategoryScope.ALL, Set.of(), null, null, null);
        registration.recordAppliedDiscountPrice(calculator.calculateDiscountedPrice(new BigDecimal("70000"), null, updated));
        assertThat(registration.getLinkPricePolicyRevision()).isEqualTo(2);
        registration.cancelParticipation();
        assertThat(registration.isSoftDeleted()).isTrue();
        assertThat(registration.getDiscountLinkId()).isEqualTo("link");
        assertThat(registration.getReferenceFilterId()).isEqualTo("filter");
        assertThat(registration.getLinkPricePolicyRevision()).isEqualTo(2);
    }
}
