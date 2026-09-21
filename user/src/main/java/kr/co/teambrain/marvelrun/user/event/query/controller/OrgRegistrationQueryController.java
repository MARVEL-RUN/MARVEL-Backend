package kr.co.teambrain.marvelrun.user.event.query.controller;

import io.swagger.v3.oas.annotations.Operation;
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
    @Operation(summary = "단체 신청 내역 조회", description = "현재 선택·수정 입력값과 결제 재개 안내를 반환합니다.")
    @PostMapping("/organizations/lookup")
    public ResponseEntity<List<OrgRegistrationQueryResponse>> lookup(
            @PathVariable("eventId") String eventId, @Valid @RequestBody OrganizationAccessRequest access) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache").body(service.organization(eventId, access));
    }
}
