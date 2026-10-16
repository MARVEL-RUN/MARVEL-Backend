package kr.co.teambrain.marvelrun.user.discount.query;

import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import java.time.OffsetDateTime;
import java.util.List;

/** 공개 링크 상태와 종목별 조건부 가격을 함께 제공한다. */
public record DiscountOptionsResponse(LinkStatus linkStatus, String message,
        DiscountModels.ApplicationType applicationType, Integer minimumGroupSize,
        OffsetDateTime acquisitionStartsAt, OffsetDateTime acquisitionEndsAt,
        ApplicantEligibility applicantEligibility, List<CategoryDiscountOptionsResponse> categories) {
    public enum LinkStatus { AVAILABLE, NOT_STARTED, EXPIRED }
    public enum ApplicantEligibility { NOT_EVALUATED }
    /** 종목 가격 적용 여부와 링크 자격 취득 여부를 혼동하지 않도록 구분한다. */
    public record CategoryDiscountOptionsResponse(String categoryId, boolean linkDiscountApplicable,
                                                  List<PriceOptionResponse> priceOptions) { }
}
