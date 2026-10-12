package kr.co.teambrain.marvelrun.user.event.query.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

/** 종목·기념품 정보와 별도로 신규 접수 정책상 허용 여부를 제공한다. */
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

            List<SouvenirOption> souvenirs
    ) {
    }


    public record SouvenirOption(

            long order,

            String souvenirId,

            String name,

            List<String> sizes
    ) {
    }
}
