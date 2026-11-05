package kr.co.teambrain.marvelrun.user.event.command.application.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import kr.co.teambrain.marvelrun.user.common.security.util.ParticipantPrincipal;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.PackageUpgradeRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.PackageUpgradeOrderResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.service.PackageUpgradeCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 로그인한 참가자의 단품 → 패키지 상향. 신청은 토큰에서 꺼내고 경로로 받지 않는다.
 * 결제 승인은 기존 공통 승인 API(POST /v1/public/payments/confirm)로 한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/participant/registrations/me/upgrade")
@PreAuthorize("hasRole('PARTICIPANT')")
public class ParticipantPackageUpgradeController {

    private final PackageUpgradeCommandService upgradeService;

    @Operation(summary = "패키지 상향 요청", description = "늘어나는 정원을 잡아 두고 차액 결제창 정보를 돌려줍니다.")
    @PostMapping
    public ResponseEntity<PackageUpgradeOrderResponse> request(@Valid @RequestBody PackageUpgradeRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(upgradeService.request(ParticipantPrincipal.registrationId(), request));
    }

    @Operation(summary = "패키지 상향 결제창 다시 열기", description = "같은 금액의 READY 주문이 있으면 그대로 돌려줍니다.")
    @PostMapping("/payment")
    public ResponseEntity<PackageUpgradeOrderResponse> preparePayment() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(upgradeService.preparePayment(ParticipantPrincipal.registrationId()));
    }

    @Operation(summary = "패키지 상향 취소", description = "결제하지 않은 상향을 취소하고 잡아 둔 정원을 돌려줍니다.")
    @DeleteMapping
    public ResponseEntity<Void> cancel() {
        upgradeService.cancel(ParticipantPrincipal.registrationId());
        return ResponseEntity.noContent().build();
    }
}
