package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 단품 → 패키지 상향 요청. 주문은 경로로 정한다.
 * itemId는 상향할 단품 구매 줄이다. 비우면 주문에 살아 있는 단품 줄이 하나일 때만 그 줄로 정한다.
 * entries에는 패키지 하위 종목 가운데 그 단품을 뺀 종목마다 받는 사람을 한 명씩 넣는다.
 */
public record PackageUpgradeRequest(
        @Schema(description = "상향할 단품 구매 줄 id(마이페이지 주문 조회의 items[].itemId). 단품이 하나뿐인 주문은 생략할 수 있다.")
        @Size(max = 40)
        String itemId,

        @NotEmpty
        @Size(max = 10)
        List<@Valid RegistrationEntryRequest> entries
) {
}
