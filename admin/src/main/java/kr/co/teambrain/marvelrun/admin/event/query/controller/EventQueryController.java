package kr.co.teambrain.marvelrun.admin.event.query.controller;

import kr.co.teambrain.marvelrun.admin.event.query.dto.EventCategoryResponse;
import kr.co.teambrain.marvelrun.admin.event.query.dto.EventListResponse;
import kr.co.teambrain.marvelrun.admin.event.query.service.EventQueryService;
import lombok.RequiredArgsConstructor;
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