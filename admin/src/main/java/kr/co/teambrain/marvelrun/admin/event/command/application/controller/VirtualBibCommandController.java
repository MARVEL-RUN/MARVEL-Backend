package kr.co.teambrain.marvelrun.admin.event.command.application.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.BibIssueRequest;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.BibIssueResponse;
import kr.co.teambrain.marvelrun.admin.event.command.application.service.BibIssueService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 버추얼런 배번 발급 API를 제공한다. */
@Tag(name = "버추얼런 배번", description = "마감된 차수의 확정 신청 참가 항목에 배번을 발급합니다.")
@RestController
@RequestMapping("/v1/admin/virtual/{eventId}/bibs")
@RequiredArgsConstructor
public class VirtualBibCommandController {

    private final BibIssueService bibIssueService;

    @Operation(summary = "차수 배번 발급",
            description = "deadline은 발급할 차수의 마감 시각입니다. 마감 뒤에만 발급할 수 있고, 한 건이라도 실패하면 전체를 취소합니다. 이미 받은 배번과 외부 결제 신청은 건너뜁니다.")
    @PostMapping("/issue")
    public ResponseEntity<BibIssueResponse> issueBibs(
            @PathVariable("eventId") String eventId,
            @Valid @RequestBody BibIssueRequest request) {
        return ResponseEntity.ok(bibIssueService.issue(eventId, request));
    }
}
