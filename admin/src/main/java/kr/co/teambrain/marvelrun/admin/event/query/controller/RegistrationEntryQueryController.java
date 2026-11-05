package kr.co.teambrain.marvelrun.admin.event.query.controller;

import io.swagger.v3.oas.annotations.Operation;
import kr.co.teambrain.marvelrun.admin.event.query.dto.response.RegistrationEntryListResponse;
import kr.co.teambrain.marvelrun.admin.event.query.service.RegistrationEntryQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자가 받는 사람 수정 전에 신청의 참가 항목을 조회하는 API를 제공한다. */
@RestController
@RequestMapping("/v1/admin")
@RequiredArgsConstructor
public class RegistrationEntryQueryController {

    private final RegistrationEntryQueryService registrationEntryQueryService;

    @GetMapping("/registrations/{registrationId}/entries")
    @Operation(summary = "신청 참가 항목 조회", description = "신청의 참가 항목을 하위 종목 순서로 돌려줍니다. entryId는 받는 사람 수정 API에 사용합니다. editable은 패키지 신청일 때만 true이며, 단품은 신청자 정보를 따르므로 기본정보 수정을 사용합니다. 삭제된 신청도 조회할 수 있고, 오프라인 대회는 항목이 없습니다.")
    public ResponseEntity<RegistrationEntryListResponse> getRegistrationEntries(@PathVariable String registrationId) {
        return ResponseEntity.ok(registrationEntryQueryService.getEntries(registrationId));
    }
}
