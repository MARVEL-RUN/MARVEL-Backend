package kr.co.teambrain.marvelrun.admin.event.query.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonInclude;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ProductType;

import java.math.BigDecimal;
import java.util.List;

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
            List<StartGroupOption> startGroups,

            /** 상품 종류. NULL이면 참가 항목이 없는 종목 */
            @Schema(description = "상품 종류(단품·패키지·옵션). null이면 참가 항목이 없는 종목")
            ProductType productType,

            /** 이 종목을 하위 종목으로 포함하는 패키지 종목 ID. 패키지 입력 칸은 이 값으로 묶는다 */
            @Schema(description = "이 종목을 하위 종목으로 포함하는 패키지 종목 id. 패키지 입력 칸은 이 값으로 묶는다. 없으면 null")
            String parentCategoryId
    ) {
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
