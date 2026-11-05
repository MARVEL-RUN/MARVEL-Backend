package kr.co.teambrain.marvelrun.admin.event.command.application.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.RegistrationEntryModifyRequest;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.RegistrationEntryModifyResponse;
import kr.co.teambrain.marvelrun.admin.event.command.application.service.RegistrationEntryCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 관리자 패키지 받는 사람 수정 API를 제공한다. */
@RestController
@RequestMapping("/v1/admin")
@RequiredArgsConstructor
public class RegistrationEntryCommandController {

    private final RegistrationEntryCommandService registrationEntryCommandService;

    @PatchMapping("/registrations/{registrationId}/entries")
    @Operation(summary = "패키지 받는 사람 수정", description = "패키지 신청의 받는 사람 이름·연락처·생년월일·성별을 수정합니다. 요청에 담은 참가 항목만 바뀌며 배번은 그대로입니다. 차수 마감 뒤에도 수정할 수 있습니다. 보호자 정보는 신청에 저장된 값으로 검증합니다. 단품은 신청자 정보를 따르므로 기본정보 수정을 사용합니다. entryId는 신청 상세의 entries[].entryId를 쓰고, 수정할 수 있는 신청인지는 상세의 entriesEditable로 판단합니다.")
    public ResponseEntity<RegistrationEntryModifyResponse> modifyRegistrationEntries(
            @PathVariable String registrationId,
            @Valid @RequestBody RegistrationEntryModifyRequest request
    ) {
        return ResponseEntity.ok(registrationEntryCommandService.modifyEntries(registrationId, request));
    }
}
