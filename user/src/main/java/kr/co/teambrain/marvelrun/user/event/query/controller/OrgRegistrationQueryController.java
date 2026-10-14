package kr.co.teambrain.marvelrun.user.event.query.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.query.dto.OrgRegistrationQueryResponse;
import kr.co.teambrain.marvelrun.user.event.query.service.RegistrationQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 단체 접수 확인과 수정 화면의 현재 정보를 조회한다. */
@Tag(name = "단체 신청 조회", description = "본인확인 후 신청 정보와 결제 안내를 조회합니다.")
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/public/events/{eventId}")
public class OrgRegistrationQueryController {
    private final RegistrationQueryService service;

    /** 민감한 인증 값은 POST 본문으로 받고 응답 캐시는 금지한다. */
    @Operation(summary = "단체 신청 내역 조회", description = "단체 계정 정보를 검증하고 단체·대표자 정보와 참가자별 신청 내역을 조회합니다. 단체 결제·환불 현황과 결제 진행 안내, 단체 및 참가자별 작업 가능 여부를 함께 제공합니다.")
    @ApiResponse(responseCode = "200", description = "본인확인된 단체·참가자 신청 내역과 결제·작업 가능 정보")
    @PostMapping("/organizations/lookup")
    public ResponseEntity<List<OrgRegistrationQueryResponse>> lookup(
            @PathVariable("eventId") String eventId, @Valid @RequestBody OrganizationAccessRequest access) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache").body(service.findOrganizationRegistrationDetails(eventId, access));
    }
}
