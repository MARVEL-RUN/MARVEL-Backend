package kr.co.teambrain.marvelrun.admin.event.query.controller;

import kr.co.teambrain.marvelrun.admin.event.query.dto.response.EventCategoryResponse;
import kr.co.teambrain.marvelrun.admin.event.query.dto.response.EventListResponse;
import kr.co.teambrain.marvelrun.admin.event.query.service.EventQueryService;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import kr.co.teambrain.marvelrun.admin.event.query.dto.response.RegistrationOptionResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/admin/events")
@RequiredArgsConstructor
public class EventQueryController {

    private final EventQueryService eventQueryService;

    /** 관리자 신청 수정 화면에 종목·기념품·그룹 선택지를 제공한다. */
    @Operation(summary = "신청 선택지 조회", description = "신청 정보 화면 구성에 필요한 종목·기념품 선택지와 안내 정보를 제공합니다. 사용자 신규 접수 가능 여부와 제한 사유를 함께 반환하며, 이 값은 관리자 수정 허용 여부가 아닙니다.")
    @ApiResponse(responseCode = "200", description = "신청 선택지 및 사용자 신규 접수 정책")
    @GetMapping("/{eventId}/registration-options")
    public ResponseEntity<RegistrationOptionResponse> getRegistrationOptions(@PathVariable("eventId") String eventId) {
        return ResponseEntity.ok(eventQueryService.getRegistrationOptions(eventId));
    }

    /**
     * 신청자 관리를 위한 대회 목록 전체 조회 API
     */
    @GetMapping
    public ResponseEntity<List<EventListResponse>> getEventList() {
        return ResponseEntity.ok(
                eventQueryService.getEventList()
        );
    }

    @GetMapping("/{eventId}/event-category")
    public ResponseEntity<List<EventCategoryResponse>> getEventCategories(
            @PathVariable String eventId
    ) {
        return ResponseEntity.ok(
                eventQueryService.getEventCategories(eventId)
        );
    }
}
