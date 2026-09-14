package kr.co.teambrain.marvelrun.user.event.query.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record RegistrationOptionResponse(

        List<EventCategoryOption> categories
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