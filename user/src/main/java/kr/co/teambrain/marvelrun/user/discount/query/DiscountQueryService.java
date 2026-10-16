package kr.co.teambrain.marvelrun.user.discount.query;

import kr.co.teambrain.marvelrun.common.discount.DiscountPriceCalculator;
import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.discount.domain.DiscountLink;
import kr.co.teambrain.marvelrun.user.discount.repository.DiscountLinkRepository;
import kr.co.teambrain.marvelrun.user.discount.repository.DiscountPolicyRepository;
import kr.co.teambrain.marvelrun.user.discount.service.DiscountPolicyResolver;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategoryCommandRepository;
import kr.co.teambrain.marvelrun.user.event.query.dto.response.RegistrationOptionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 신청 생성과 독립적으로 조건별 가격 안내를 계산한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DiscountQueryService {
    private final DiscountPolicyResolver resolver;
    private final DiscountLinkRepository links;
    private final DiscountPolicyRepository policies;
    private final EventCategoryCommandRepository categories;
    private final ServerTimeProvider time;
    private final DiscountPriceCalculator calculator = new DiscountPriceCalculator();

    /** 기존 종목·기념품 필드를 보존하면서 서비스 내 가격 안내만 추가한다. */
    public RegistrationOptionResponse appendInternalPricingOptions(String eventId, RegistrationOptionResponse original) {
        List<DiscountModels.Policy> snapshot = resolver.resolveEventPolicies(eventId);
        List<RegistrationOptionResponse.EventCategoryOption> options = original.categories().stream().map(category ->
                new RegistrationOptionResponse.EventCategoryOption(category.order(), category.categoryId(), category.distance(),
                        category.categoryName(), category.isActive(), category.amount(), category.souvenirs(), category.startGroups(),
                        new RegistrationPricingOptionsResponse(priceOptions(category.categoryId(), category.amount(), snapshot, null)))).toList();
        return new RegistrationOptionResponse(options, original.registrationAllowed(), original.registrationReason());
    }

    /** 정상 링크의 기간 전/후도 안내하되 잘못된 대회·코드는 조회 실패로 처리한다. */
    public DiscountOptionsResponse getExternalDiscountOptions(String eventId, String code) {
        DiscountLink link = links.findByCode(code.strip()).filter(row -> row.getCode().equals(code.strip()))
                .orElseThrow(() -> new CustomException(ErrorCode.DISCOUNT_LINK_NOT_FOUND));
        if (!policies.findById(link.getPolicyId()).orElseThrow(() -> new CustomException(ErrorCode.DISCOUNT_CONFIGURATION_ERROR))
                .getEventId().equals(eventId)) {
            throw new CustomException(ErrorCode.DISCOUNT_LINK_NOT_FOUND);
        }
        List<DiscountModels.Policy> snapshot = resolver.resolveEventPolicies(eventId);
        DiscountModels.Policy external = resolver.resolveLinkPolicy(link, eventId, snapshot);
        LocalDateTime now = time.currentDateTime();
        DiscountOptionsResponse.LinkStatus status = now.isBefore(link.getAcquisitionStartAt())
                ? DiscountOptionsResponse.LinkStatus.NOT_STARTED : !now.isBefore(link.getAcquisitionEndAt())
                ? DiscountOptionsResponse.LinkStatus.EXPIRED : DiscountOptionsResponse.LinkStatus.AVAILABLE;
        List<DiscountOptionsResponse.CategoryDiscountOptionsResponse> results = categories.findByEvent_Id(eventId).stream()
                .sorted(Comparator.comparing(EventCategory::getOrder).thenComparing(EventCategory::getId))
                .map(category -> new DiscountOptionsResponse.CategoryDiscountOptionsResponse(category.getId(),
                        external.categoryScope() == DiscountModels.CategoryScope.ALL || external.categoryIds().contains(category.getId()),
                        priceOptions(category.getId(), category.getAmount(), snapshot, external))).toList();
        String message = switch (status) {
            case AVAILABLE -> "조건을 충족하는 경우의 예상 참가비입니다. 신청 시 인원과 참가자 정보를 다시 검증합니다.";
            case NOT_STARTED -> "아직 할인 자격 취득 기간이 아닙니다. 표시 금액은 조건 충족을 가정한 안내입니다.";
            case EXPIRED -> "신규 할인 자격 취득 기간이 종료되었습니다. 표시 금액은 현재 신규 신청 가능 금액이 아닙니다.";
        };
        return new DiscountOptionsResponse(status, message, external.applicationType(), external.minimumGroupSize(),
                link.getAcquisitionStartAt().atOffset(ZoneOffset.ofHours(9)), link.getAcquisitionEndAt().atOffset(ZoneOffset.ofHours(9)),
                DiscountOptionsResponse.ApplicantEligibility.NOT_EVALUATED, results);
    }

    /** 개인·단체 유형과 연령 조건을 명시하여 조건부 가격을 혼동하지 않게 한다. */
    private List<PriceOptionResponse> priceOptions(String categoryId, BigDecimal base,
            List<DiscountModels.Policy> snapshot, DiscountModels.Policy external) {
        List<PriceOptionResponse> result = new ArrayList<>();
        for (DiscountModels.ApplicationType type : List.of(DiscountModels.ApplicationType.PERSONAL, DiscountModels.ApplicationType.ORGANIZATION)) {
            boolean organization = type == DiscountModels.ApplicationType.ORGANIZATION;
            if (external != null && external.applicationType() != DiscountModels.ApplicationType.BOTH && external.applicationType() != type) {
                continue;
            }
            DiscountModels.Policy appliedExternal = external != null && external.targets(categoryId, organization) ? external : null;
            List<DiscountModels.Policy> internal = snapshot.stream().filter(policy -> policy.stage() == DiscountModels.Stage.INTERNAL
                    && policy.targets(categoryId, organization)).toList();
            result.add(describePrice("STANDARD", "모든 서비스 내 연령 할인 조건에 해당하지 않는 경우", type, base, null, appliedExternal));
            for (DiscountModels.Policy policy : internal) {
                if (internal.stream().anyMatch(other -> !other.id().equals(policy.id())
                        && other.minAgeInclusive() < policy.maxAgeExclusive() && policy.minAgeInclusive() < other.maxAgeExclusive())) {
                    throw new CustomException(ErrorCode.DISCOUNT_CONFIGURATION_ERROR);
                }
                result.add(describePrice("AGE_CONDITION", "대회일 기준 만 " + policy.minAgeInclusive() + "세 이상 "
                        + policy.maxAgeExclusive() + "세 미만인 경우", type, base, policy, appliedExternal));
            }
        }
        return List.copyOf(result);
    }

    /** 계산 실패를 정상 금액으로 노출하지 않는다. */
    private PriceOptionResponse describePrice(String scenario, String description, DiscountModels.ApplicationType type,
            BigDecimal base, DiscountModels.Policy internal, DiscountModels.Policy external) {
        DiscountModels.PriceResult result = calculator.calculateDiscountedPrice(base, internal, external);
        if (result.failure() != DiscountModels.Failure.NONE) {
            throw new CustomException(result.failure() == DiscountModels.Failure.ZERO_AMOUNT
                    ? ErrorCode.DISCOUNT_ZERO_AMOUNT_NOT_SUPPORTED : ErrorCode.DISCOUNT_CONFIGURATION_ERROR);
        }
        return new PriceOptionResponse(scenario, description, type, base, result.finalAmount(), result.roundingAdjustmentAmount(),
                result.appliedDiscounts().stream().map(AppliedDiscountResponse::from).toList());
    }
}
