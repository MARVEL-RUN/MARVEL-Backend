package kr.co.teambrain.marvelrun.user.event.command.application.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 단품 → 패키지 상향 요청. 신청은 참가자 토큰으로 정한다.
 * entries에는 패키지 하위 종목 가운데 지금 단품을 뺀 종목마다 받는 사람을 한 명씩 넣는다.
 */
public record PackageUpgradeRequest(
        @NotEmpty
        @Size(max = 10)
        List<@Valid RegistrationEntryRequest> entries
) {
}
