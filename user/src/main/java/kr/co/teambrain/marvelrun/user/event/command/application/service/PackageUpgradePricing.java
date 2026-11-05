package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import kr.co.teambrain.marvelrun.user.discount.domain.DiscountLink;
import kr.co.teambrain.marvelrun.user.discount.service.DiscountPolicyResolver;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 상향할 패키지의 가격을 신청·수정과 같은 계산으로 정한다.
 *
 * 상향 요청(차액 결정), 승인 전 검사, 승인 때 가격 계산 기록이 같은 값을 쓰도록 한 곳에 둔다.
 * 상품 가격이므로 연령 할인 없이 신청에 저장된 할인 링크만 적용한다(신청 때 상품 줄 가격과 같은 규칙).
 */
@Component
@RequiredArgsConstructor
public class PackageUpgradePricing {

    private final RegistrationPricingService registrationPricingService;
    private final DiscountPolicyResolver discountPolicies;

    public DiscountModels.PriceResult calculate(Registration registration, EventCategory packageCategory) {
        DiscountLink link = registration.getDiscountLinkId() == null ? null
                : discountPolicies.resolveStoredLink(registration.getDiscountLinkId());
        return registrationPricingService.calculateProductPrice(
                registration.getEvent(),
                packageCategory,
                link,
                discountPolicies.resolveEventPolicies(registration.getEvent().getId())
        );
    }
}
