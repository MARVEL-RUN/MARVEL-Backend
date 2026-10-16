package kr.co.teambrain.marvelrun.admin.discount;

import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import kr.co.teambrain.marvelrun.admin.discount.service.DiscountPolicyResolver;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 기존 어린이 정책 이관 동등성 테스트에 사용할 명시적 DB 조회 대역이다. */
public final class PricingPolicyFixtures {
    private PricingPolicyFixtures() { }
    /** 운영 코드의 하드코딩과 달리 테스트 입력에서 기존 두 정책을 명시한다. */
    public static DiscountPolicyResolver resolver() {
        DiscountPolicyResolver resolver = mock(DiscountPolicyResolver.class);
        when(resolver.resolveEventPolicies(anyString())).thenAnswer(call -> {
            String eventId = call.getArgument(0);
            if (!List.of("test-marvelrun", "marvelrun2026").contains(eventId)) {
                return List.of();
            }
            return List.of(new DiscountModels.Policy("child-policy", eventId, 1, DiscountModels.Stage.INTERNAL,
                    DiscountModels.Method.FIXED_PRICE, null, new BigDecimal("40000"), DiscountModels.ApplicationType.BOTH,
                    DiscountModels.CategoryScope.ALL, Set.of(), null, 0, 13));
        });
        return resolver;
    }
}