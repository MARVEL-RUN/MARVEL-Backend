package kr.co.teambrain.marvelrun.user.event.query.controller;

import io.swagger.v3.oas.annotations.Operation;
import kr.co.teambrain.marvelrun.user.common.security.util.ParticipantPrincipal;
import kr.co.teambrain.marvelrun.user.event.query.dto.ParticipantOrderResponse;
import kr.co.teambrain.marvelrun.user.event.query.service.ParticipantOrderQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 로그인한 주문자의 마이페이지 주문 조회. 주문은 토큰의 주문자 키로 찾는다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/participant/registrations")
@PreAuthorize("hasRole('PARTICIPANT')")
public class ParticipantOrderQueryController {

    private final ParticipantOrderQueryService orders;

    @Operation(summary = "내 주문 목록", description = "로그인한 주문자의 주문을 모두 돌려줍니다(취소한 주문 포함). "
            + "살아 있는 주문, 최근 신청 순입니다. order는 공개 신청 조회 응답과 같은 모양이고, "
            + "구매 줄(items[].entries[], 배번)과 수정·환불 마감 시각을 더했습니다.")
    @GetMapping
    public ResponseEntity<List<ParticipantOrderResponse>> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(orders.findOrders(ParticipantPrincipal.ordererKey()));
    }

    @Operation(summary = "내 주문 하나", description = "다른 주문자의 주문은 REGISTRATION_ACCESS_DENIED로 거절합니다.")
    @GetMapping("/{registrationId}")
    public ResponseEntity<ParticipantOrderResponse> detail(@PathVariable("registrationId") String registrationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(orders.findOrder(registrationId, ParticipantPrincipal.ordererKey()));
    }
}
