package kr.co.teambrain.marvelrun.admin.event.command.application.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.VirtualRoundsRequest;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.VirtualRoundsResponse;
import kr.co.teambrain.marvelrun.admin.event.command.application.service.VirtualRoundService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 버추얼런 차수 조회와 교체 API를 제공한다. */
@Tag(name = "버추얼런 차수", description = "버추얼런 대회의 차수(신청 시작~마감)를 조회하고 한 번에 교체합니다.")
@RestController
@RequestMapping("/v1/admin/virtual/{eventId}/rounds")
@RequiredArgsConstructor
public class VirtualRoundController {

    private final VirtualRoundService virtualRoundService;

    @Operation(summary = "차수 조회", description = "활성 정책에서 읽은 차수 목록입니다. deadline은 차수 마감(수정·결제·환불 적용 개시) 시각입니다.")
    @GetMapping
    public ResponseEntity<VirtualRoundsResponse> getRounds(@PathVariable("eventId") String eventId) {
        return ResponseEntity.ok(virtualRoundService.getRounds(eventId));
    }

    @Operation(summary = "차수 교체",
            description = "차수 전체를 요청 값으로 교체합니다. 마감된 차수는 같은 값으로 포함해야 하며 바꿀 수 없습니다. 차수 사이 구간은 신규 신청 차단 구간으로 자동 생성합니다.")
    @PutMapping
    public ResponseEntity<VirtualRoundsResponse> replaceRounds(
            @PathVariable("eventId") String eventId,
            @Valid @RequestBody VirtualRoundsRequest request) {
        return ResponseEntity.ok(virtualRoundService.replaceRounds(eventId, request));
    }
}
