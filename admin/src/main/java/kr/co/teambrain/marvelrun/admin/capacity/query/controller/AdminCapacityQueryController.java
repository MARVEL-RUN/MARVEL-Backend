package kr.co.teambrain.marvelrun.admin.capacity.query.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.teambrain.marvelrun.admin.capacity.query.dto.CapacityParticipantPageResponse;
import kr.co.teambrain.marvelrun.admin.capacity.query.dto.CapacityParticipantState;
import kr.co.teambrain.marvelrun.admin.capacity.query.dto.CapacityQueryResponse;
import kr.co.teambrain.marvelrun.admin.capacity.query.service.AdminCapacityQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 관리자의 대회 정원 현황과 정원별 참가자 조회 API이다. */
@Tag(name = "관리자 정원 조회", description = "대회의 정원 한도·홀딩·확정 수량과 점유 참가자를 조회합니다.")
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/admin/events/{eventId}/capacities")
public class AdminCapacityQueryController {
    private final AdminCapacityQueryService service;

    /** 대회 전체 정원 제한을 목록으로 반환한다. */
    @Operation(summary = "대회 정원 현황 조회", description = "대회 총원, 종목, 상세 제약, 기념품의 최대·홀딩·확정 수량을 반환합니다.")
    @GetMapping
    public ResponseEntity<List<CapacityQueryResponse>> capacities(@PathVariable("eventId") String eventId) {
        return noStore(service.capacities(eventId));
    }

    /** 지정한 정원의 홀딩 또는 확정 참가자를 페이지 조회한다. */
    @Operation(summary = "정원별 참가자 조회", description = "HELD는 결제 처리 중을 포함한 홀딩, CONFIRMED는 확정 참가자입니다. 개인의 단체명은 null입니다.")
    @GetMapping("/{capacityId}/registrations")
    public ResponseEntity<CapacityParticipantPageResponse> participants(
            @PathVariable("eventId") String eventId,
            @PathVariable("capacityId") String capacityId,
            @RequestParam("state") CapacityParticipantState state,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return noStore(service.participants(eventId, capacityId, state, page, size));
    }

    /** 개인정보와 변동하는 정원 정보가 HTTP 캐시에 저장되지 않도록 한다. */
    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache").body(body);
    }
}
