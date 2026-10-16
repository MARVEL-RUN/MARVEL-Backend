package kr.co.teambrain.marvelrun.user.discount.query;

import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import java.time.OffsetDateTime;
import java.util.List;

/** 공개 링크 상태와 종목별 조건부 가격을 함께 제공한다. */
public record DiscountOptionsResponse(
        LinkStatus linkStatus,
        String message,
        DiscountModels.ApplicationType applicationType,
        Integer minimumGroupSize,
        OffsetDateTime acquisitionStartsAt,
        OffsetDateTime acquisitionEndsAt,
        ApplicantEligibility applicantEligibility,
        List<CategoryDiscountOptionsResponse> categories
) {
    /** 링크 자격의 신규 취득 기간 상태이며 기존 신청의 할인 적용 만료가 아니다. */
    public enum LinkStatus {
        AVAILABLE, NOT_STARTED, EXPIRED
    }

    /** 안내 조회만으로 실제 참가자 자격이 확정되지 않았음을 명시한다. */
    public enum ApplicantEligibility {
        NOT_EVALUATED
    }

    /** 종목 가격 적용 여부와 링크 자격 취득 여부를 혼동하지 않도록 구분한다. */
    public record CategoryDiscountOptionsResponse(
            String categoryId,
            boolean linkDiscountApplicable,
            List<PriceOptionResponse> priceOptions
    ) {
    }
}
