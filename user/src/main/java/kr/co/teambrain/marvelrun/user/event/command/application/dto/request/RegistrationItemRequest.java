package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;

import java.util.List;

/**
 * 구매 줄 하나(상품 한 개). 같은 상품을 두 개 사면 줄을 두 번 보낸다.
 *
 * 단품은 그 종목의 받는 사람 1명, 패키지는 하위 종목마다 받는 사람 1명을 보낸다.
 */
public record RegistrationItemRequest(

        /** 구매할 상품(단품·패키지) 종목 ID */
        @NotBlank
        @Size(max = 40)
        String eventCategoryId,

        @NotEmpty
        List<@NotNull @Valid SouvenirJson> selectedSouvenirList,

        List<@NotNull @Valid RegistrationEntryRequest> entries
) {
}
