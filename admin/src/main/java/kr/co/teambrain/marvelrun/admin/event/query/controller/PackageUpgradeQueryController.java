package kr.co.teambrain.marvelrun.admin.event.query.controller;

import io.swagger.v3.oas.annotations.Operation;
import kr.co.teambrain.marvelrun.admin.event.query.dto.PendingPackageUpgradeItem;
import kr.co.teambrain.marvelrun.admin.event.query.service.AdminPackageUpgradeQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 버추얼런 단품 → 패키지 상향 대기 조회. 취소는 PackageUpgradeAdminController가 맡는다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/admin/virtual/{eventId}/upgrades")
public class PackageUpgradeQueryController {

    private final AdminPackageUpgradeQueryService queryService;

    @Operation(summary = "상향 대기 목록", description = "결제를 기다리는 상향과 마지막 상향 주문의 결제 상태를 보여 줍니다.")
    @GetMapping
    public ResponseEntity<List<PendingPackageUpgradeItem>> pending(@PathVariable("eventId") String eventId) {
        return ResponseEntity.ok(queryService.findPending(eventId));
    }
}
