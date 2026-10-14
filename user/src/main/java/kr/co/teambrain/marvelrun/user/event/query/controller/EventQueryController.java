package kr.co.teambrain.marvelrun.user.event.query.controller;


import kr.co.teambrain.marvelrun.user.event.query.dto.response.RegistrationOptionResponse;
import kr.co.teambrain.marvelrun.user.event.query.service.EventQueryService;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 종목·기념품·그룹 선택지와 기존 접수 정책을 제공한다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/public")
public class EventQueryController {

    private final EventQueryService eventQueryService;


    /** 그룹 ID와 DB 안내문을 종목별 선택지에 포함한다. */
    @Operation(summary = "신청 선택지 조회", description = "대회 신청 화면 구성에 필요한 종목·기념품·그룹 선택지와 안내 정보를 제공합니다. 신규 접수 가능 여부와 제한 사유를 함께 반환합니다.")
    @ApiResponse(responseCode = "200", description = "신청 선택지 및 접수 정책")
    @GetMapping("/events/{eventId}/registration-options")
    public ResponseEntity<RegistrationOptionResponse> getRegistrationOptions(
            @PathVariable("eventId") String eventId
    ) {
        return ResponseEntity.ok(eventQueryService.getRegistrationOptions(eventId));
    }
}
