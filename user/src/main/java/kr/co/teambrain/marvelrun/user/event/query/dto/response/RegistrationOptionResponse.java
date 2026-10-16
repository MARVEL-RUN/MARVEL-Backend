package kr.co.teambrain.marvelrun.user.event.query.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonIgnore;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;
import kr.co.teambrain.marvelrun.user.discount.query.RegistrationPricingOptionsResponse;

/** 종목·기념품·그룹 선택지와 별도로 신규 접수 정책상 허용 여부를 제공한다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RegistrationOptionResponse(

        List<EventCategoryOption> categories,
        boolean registrationAllowed,
        RegistrationRestrictionReasonResponse registrationReason
) {

    public record EventCategoryOption(

            long order,

            String categoryId,

            String distance,

            String categoryName,

            Boolean isActive,

            BigDecimal amount,

            List<SouvenirOption> souvenirs,
            // 그룹 기능 임시 중단: 내부 호환 필드는 JSON·Swagger에서 제외한다.
            @JsonIgnore @Schema(hidden = true)
            List<StartGroupOption> startGroups,
            RegistrationPricingOptionsResponse pricing
    ) {
        /** 기존 종목·기념품 조립 호출을 유지하고 가격 안내는 조회 서비스가 추가한다. */
        public EventCategoryOption(long order, String categoryId, String distance, String categoryName,
                Boolean isActive, BigDecimal amount, List<SouvenirOption> souvenirs, List<StartGroupOption> startGroups) {
            this(order, categoryId, distance, categoryName, isActive, amount, souvenirs, startGroups, null);
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
