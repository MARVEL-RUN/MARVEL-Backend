package kr.co.teambrain.marvelrun.user.event.query.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;
import kr.co.teambrain.marvelrun.common.inheritance_enum.EventFormat;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ProductType;
import kr.co.teambrain.marvelrun.user.discount.query.RegistrationPricingOptionsResponse;

/** 종목·기념품·그룹 선택지와 별도로 신규 접수 정책상 허용 여부를 제공한다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RegistrationOptionResponse(

        List<EventCategoryOption> categories,
        boolean registrationAllowed,
        RegistrationRestrictionReasonResponse registrationReason,

        /** 화면 구분용 대회 형식 */
        EventFormat eventFormat,

        /** 개인 신청에 주소가 필수인지 */
        boolean addressRequired,

        /** 단체 신청을 받는지 */
        boolean organizationRegistrationEnabled
) {

    /** 대회 단위 값은 유지하고 종목 목록만 바꾼다. */
    public RegistrationOptionResponse withCategories(List<EventCategoryOption> categories) {
        return new RegistrationOptionResponse(categories, registrationAllowed, registrationReason,
                eventFormat, addressRequired, organizationRegistrationEnabled);
    }

    public record EventCategoryOption(

            long order,

            String categoryId,

            String distance,

            String categoryName,

            Boolean isActive,

            BigDecimal amount,

            List<SouvenirOption> souvenirs,
            List<StartGroupOption> startGroups,
            RegistrationPricingOptionsResponse pricing,

            /** 상품 종류. NULL이면 참가 항목이 없는 종목 */
            ProductType productType,

            /** 이 종목을 하위 종목으로 포함하는 패키지 종목 ID. 패키지 입력 칸은 이 값으로 묶는다 */
            String parentCategoryId
    ) {
        /** 가격 안내 없이 종목·기념품을 조립하고 가격 안내는 조회 서비스가 추가한다. */
        public EventCategoryOption(long order, String categoryId, String distance, String categoryName,
                Boolean isActive, BigDecimal amount, List<SouvenirOption> souvenirs, List<StartGroupOption> startGroups,
                ProductType productType, String parentCategoryId) {
            this(order, categoryId, distance, categoryName, isActive, amount, souvenirs, startGroups, null,
                    productType, parentCategoryId);
        }

        /** 다른 값은 유지하고 가격 안내만 바꾼다. */
        public EventCategoryOption withPricing(RegistrationPricingOptionsResponse pricing) {
            return new EventCategoryOption(order, categoryId, distance, categoryName, isActive, amount,
                    souvenirs, startGroups, pricing, productType, parentCategoryId);
        }
    }


    /** DB에 저장된 그룹 식별자와 안내문을 그대로 제공한다. */
    public record StartGroupOption(String id, String code, String description) { }

    public record SouvenirOption(

            long order,

            String souvenirId,

            String name,

            List<String> sizes
    ) {
    }
}
