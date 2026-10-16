package kr.co.teambrain.marvelrun.admin.event.command.application.service;

import kr.co.teambrain.marvelrun.common.discount.DiscountPriceCalculator;
import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.discount.domain.DiscountLink;
import kr.co.teambrain.marvelrun.admin.discount.service.DiscountPolicyResolver;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
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

        // 자격을 보유해도 현재 종목·신청 유형이 비대상이면 이번 가격에는 적용하지 않는다.
        DiscountModels.Policy external = link == null ? null : resolver.resolveLinkPolicy(link, event.getId(), snapshot);
        if (external != null && !external.targets(category.getId(), organization)) {
            external = null;
        }

        // 금액 확정 전에 실패를 업무 예외로 변환하여 전체 신청을 롤백한다.
        DiscountModels.PriceResult result = calculator.calculateDiscountedPrice(category.getAmount(),
            matching.isEmpty() ? null : matching.getFirst(), external);
        if (result.failure() == DiscountModels.Failure.ZERO_AMOUNT) {
            throw new CustomException(ErrorCode.DISCOUNT_ZERO_AMOUNT_NOT_SUPPORTED);
        }
        if (result.failure() != DiscountModels.Failure.NONE) {
            throw new CustomException(ErrorCode.DISCOUNT_CONFIGURATION_ERROR);
        }
        return result;
    }
}
