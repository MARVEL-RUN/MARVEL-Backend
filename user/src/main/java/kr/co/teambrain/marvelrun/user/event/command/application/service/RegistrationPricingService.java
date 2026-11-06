package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.common.discount.DiscountPriceCalculator;
import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.discount.domain.DiscountLink;
import kr.co.teambrain.marvelrun.user.discount.service.DiscountPolicyResolver;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** DB 할인 정책을 공통 계산기에 전달하며 모듈의 업무 오류로 변환한다. */
@Service
@RequiredArgsConstructor
public class RegistrationPricingService {
    private final DiscountPolicyResolver resolver;
    private final DiscountPriceCalculator calculator = new DiscountPriceCalculator();

    /** 링크 자격이 없는 기존 호출 경로의 내부 할인 가격을 산정한다. */
    public BigDecimal calculateContractAmount(Event event, EventCategory category, String birthText) {
        return calculateRegistrationPrice(event, category, birthText, false, null,
            resolver.resolveEventPolicies(event.getId())).finalAmount();
    }

    /** 참가자별 가격 및 적용 개정을 산정하며 대회 정책 조회 결과를 재사용한다. */
    public DiscountModels.PriceResult calculateRegistrationPrice(Event event, EventCategory category, String birthText,
        boolean organization, DiscountLink link, List<DiscountModels.Policy> snapshot) {
        // 입력 정보에 따라 내부 정책을 하나만 선택한다.
        LocalDate birth = LocalDate.parse(birthText);
        List<DiscountModels.Policy> matching = snapshot.stream()
            .filter(policy -> policy.stage() == DiscountModels.Stage.INTERNAL)
            .filter(policy -> policy.targets(category.getId(), organization))
            .filter(policy -> policy.matchesAge(birth, event.getStartDate().toLocalDate())).toList();
        if (matching.size() > 1) {
            throw new CustomException(ErrorCode.DISCOUNT_CONFIGURATION_ERROR);
        }
        return price(event, category, organization, matching.isEmpty() ? null : matching.getFirst(), link, snapshot);
    }

    /**
     * 상품(단품·패키지) 한 개의 가격.
     *
     * 상품은 연령 할인을 하지 않는다. 내부 할인은 모두 연령 조건 정책이므로(DiscountModels.Policy.matchesAge)
     * 내부 할인 단계를 건너뛰고 할인 링크만 적용한다. 그래서 신청자·받는 사람 생년월일이 바뀌어도 가격은 그대로다.
     */
    public DiscountModels.PriceResult calculateProductPrice(Event event, EventCategory product, DiscountLink link,
        List<DiscountModels.Policy> snapshot) {
        return price(event, product, false, null, link, snapshot);
    }

    private DiscountModels.PriceResult price(Event event, EventCategory category, boolean organization,
        DiscountModels.Policy internal, DiscountLink link, List<DiscountModels.Policy> snapshot) {
        // 자격을 보유해도 현재 종목·신청 유형이 비대상이면 이번 가격에는 적용하지 않는다.
        DiscountModels.Policy external = link == null ? null : resolver.resolveLinkPolicy(link, event.getId(), snapshot);
        if (external != null && !external.targets(category.getId(), organization)) {
            external = null;
        }

        // 금액 확정 전에 실패를 업무 예외로 변환하여 전체 신청을 롤백한다.
        DiscountModels.PriceResult result = calculator.calculateDiscountedPrice(category.getAmount(),
            internal, external);
        if (result.failure() == DiscountModels.Failure.ZERO_AMOUNT) {
            throw new CustomException(ErrorCode.DISCOUNT_ZERO_AMOUNT_NOT_SUPPORTED);
        }
        if (result.failure() != DiscountModels.Failure.NONE) {
            throw new CustomException(ErrorCode.DISCOUNT_CONFIGURATION_ERROR);
        }
        return result;
    }

    /**
     * 구매 줄 가격의 단순 합. 묶음 할인은 없다.
     * 기준·최종·반올림 금액은 더하고 할인 내역은 줄 순서대로 잇는다. 줄 가격은 모두 계산에 성공한 값이어야 한다.
     */
    public static DiscountModels.PriceResult sum(List<DiscountModels.PriceResult> prices) {
        if (prices.isEmpty()) {
            throw new CustomException(ErrorCode.DISCOUNT_CONFIGURATION_ERROR);
        }
        BigDecimal base = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal rounding = BigDecimal.ZERO;
        List<DiscountModels.AppliedDiscount> discounts = new ArrayList<>();
        for (DiscountModels.PriceResult price : prices) {
            if (price.failure() != DiscountModels.Failure.NONE) {
                throw new CustomException(ErrorCode.DISCOUNT_CONFIGURATION_ERROR);
            }
            base = base.add(price.baseAmount());
            total = total.add(price.finalAmount());
            rounding = rounding.add(price.roundingAdjustmentAmount());
            discounts.addAll(price.appliedDiscounts());
        }
        return new DiscountModels.PriceResult(DiscountModels.Failure.NONE, base, total, rounding, List.copyOf(discounts));
    }
}
