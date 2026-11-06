package kr.co.teambrain.marvelrun.user.event.command.application.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import kr.co.teambrain.marvelrun.user.common.security.util.ParticipantPrincipal;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.PackageUpgradeRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.PackageUpgradeOrderResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.service.PackageUpgradeCommandService;
import kr.co.teambrain.marvelrun.user.participantauth.command.application.service.ParticipantOrderAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 로그인한 주문자의 단품 → 패키지 상향. 주문은 경로로 받고, 서비스가 잠금 뒤 토큰 주문자의 주문인지 본다.
 * 결제 승인은 기존 공통 승인 API(POST /v1/public/payments/confirm)로 한다.
 * me/upgrade는 프론트가 {registrationId} 경로로 옮길 때까지 남긴다. 살아 있는 주문이 하나일 때만 쓸 수 있다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/participant/registrations")
@PreAuthorize("hasRole('PARTICIPANT')")
public class ParticipantPackageUpgradeController {

    private final PackageUpgradeCommandService upgradeService;
    private final ParticipantOrderAccess orderAccess;

    @Operation(summary = "패키지 상향 요청", description = "늘어나는 정원을 잡아 두고 차액 결제창 정보를 돌려줍니다.")
    @PostMapping("/{registrationId}/upgrade")
    public ResponseEntity<PackageUpgradeOrderResponse> request(@PathVariable("registrationId") String registrationId,
                                                               @Valid @RequestBody PackageUpgradeRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(upgradeService.request(registrationId, ParticipantPrincipal.ordererKey(), request));
    }

    @Operation(summary = "패키지 상향 결제창 다시 열기", description = "같은 금액의 READY 주문이 있으면 그대로 돌려줍니다.")
    @PostMapping("/{registrationId}/upgrade/payment")
    public ResponseEntity<PackageUpgradeOrderResponse> preparePayment(@PathVariable("registrationId") String registrationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(upgradeService.preparePayment(registrationId, ParticipantPrincipal.ordererKey()));
    }

    @Operation(summary = "패키지 상향 취소", description = "결제하지 않은 상향을 취소하고 잡아 둔 정원을 돌려줍니다.")
    @DeleteMapping("/{registrationId}/upgrade")
    public ResponseEntity<Void> cancel(@PathVariable("registrationId") String registrationId) {
        upgradeService.cancel(registrationId, ParticipantPrincipal.ordererKey());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "패키지 상향 요청(이전 경로)", deprecated = true,
            description = "{registrationId}/upgrade로 옮겨 주세요. 살아 있는 주문이 하나일 때만 쓸 수 있습니다.")
    @PostMapping("/me/upgrade")
    public ResponseEntity<PackageUpgradeOrderResponse> requestMe(@Valid @RequestBody PackageUpgradeRequest request) {
        String ordererKey = ParticipantPrincipal.ordererKey();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(upgradeService.request(orderAccess.resolveMe(ordererKey), ordererKey, request));
    }

    @Operation(summary = "패키지 상향 결제창 다시 열기(이전 경로)", deprecated = true,
            description = "{registrationId}/upgrade/payment로 옮겨 주세요.")
    @PostMapping("/me/upgrade/payment")
    public ResponseEntity<PackageUpgradeOrderResponse> preparePaymentMe() {
        String ordererKey = ParticipantPrincipal.ordererKey();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(upgradeService.preparePayment(orderAccess.resolveMe(ordererKey), ordererKey));
    }

    @Operation(summary = "패키지 상향 취소(이전 경로)", deprecated = true,
            description = "{registrationId}/upgrade로 옮겨 주세요.")
    @DeleteMapping("/me/upgrade")
    public ResponseEntity<Void> cancelMe() {
        String ordererKey = ParticipantPrincipal.ordererKey();
        upgradeService.cancel(orderAccess.resolveMe(ordererKey), ordererKey);
        return ResponseEntity.noContent().build();
    }
}
