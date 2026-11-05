package kr.co.teambrain.marvelrun.admin.event.command.application.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.PackageUpgradeBatchCancellationRequest;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.PackageUpgradeBatchCancellationResponse;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.PackageUpgradeCancellationResult;
import kr.co.teambrain.marvelrun.admin.event.command.application.service.AdminPackageUpgradeBatchService;
import kr.co.teambrain.marvelrun.admin.event.command.application.service.AdminPackageUpgradeCancellationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 버추얼런 단품 → 패키지 상향 대기 취소. 대기 목록 조회는 PackageUpgradeQueryController가 맡는다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/admin/virtual/{eventId}/upgrades")
public class PackageUpgradeAdminController {

    private final AdminPackageUpgradeCancellationService cancellationService;
    private final AdminPackageUpgradeBatchService batchService;

    @Operation(summary = "상향 대기 단건 취소", description = "READY 주문을 무효로 하고 잡아 둔 정원을 돌려준 뒤 단품으로 되돌립니다.")
    @PostMapping("/{upgradeId}/cancellation")
    public ResponseEntity<PackageUpgradeCancellationResult> cancel(
            @PathVariable("eventId") String eventId,
            @PathVariable("upgradeId") String upgradeId
    ) {
        return ResponseEntity.ok(cancellationService.cancel(eventId, upgradeId));
    }

    @Operation(summary = "남은 상향 대기 일괄 취소",
            description = "차수 마감 뒤 배번 발급 전에 그 마감 전에 신청한 상향 대기를 최대 50건씩 취소합니다. 응답의 nextAfterUpgradeId를 afterUpgradeId로 넘겨 null이 될 때까지 다시 부릅니다. 실패한 건은 건너뛰고 결과 목록에 사유를 남깁니다.")
    @PostMapping("/cancellation")
    public ResponseEntity<PackageUpgradeBatchCancellationResponse> cancelPending(
            @PathVariable("eventId") String eventId,
            @Valid @RequestBody PackageUpgradeBatchCancellationRequest request
    ) {
        return ResponseEntity.ok(batchService.cancelPendingBeforeDeadline(eventId, request));
    }
}
