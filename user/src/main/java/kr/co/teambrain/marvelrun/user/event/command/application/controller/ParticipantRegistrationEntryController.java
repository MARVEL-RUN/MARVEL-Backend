package kr.co.teambrain.marvelrun.user.event.command.application.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import kr.co.teambrain.marvelrun.user.common.security.util.ParticipantPrincipal;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationEntryModifyRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.RegistrationEntryListResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.RegistrationEntryModifyResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationEntryModificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 로그인한 참가자의 받는 사람 조회·수정. 신청은 토큰에서 꺼내고 경로로 받지 않는다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/participant/registrations/me/entries")
@PreAuthorize("hasRole('PARTICIPANT')")
public class ParticipantRegistrationEntryController {

    private final RegistrationEntryModificationService entryModifications;

    @Operation(summary = "받는 사람 목록", description = "상품 신청의 받는 사람(참가 항목)을 구매 줄 순서 → 종목 순서로 돌려줍니다. "
            + "받는 사람 수정에 쓰는 entryId가 들어 있습니다. 일반 종목 신청은 빈 목록입니다.")
    @GetMapping
    public ResponseEntity<RegistrationEntryListResponse> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(entryModifications.findEntries(ParticipantPrincipal.registrationId()));
    }

    @Operation(summary = "받는 사람 수정", description = "상품(단품·패키지) 신청의 받는 사람 이름·연락처·생년월일·성별을 수정합니다. entryId는 받는 사람 목록(GET)에서 읽습니다. "
            + "요청에 담은 참가 항목만 바뀌고 가격·정원·배번은 그대로입니다. 신청자 정보 수정과 같은 수정 기간 안에서만 할 수 있습니다. "
            + "보호자 정보는 신청에 저장된 값으로 검증합니다. 한 명이라도 거절되면 아무것도 바뀌지 않습니다.")
    @PatchMapping
    public ResponseEntity<RegistrationEntryModifyResponse> modify(@Valid @RequestBody RegistrationEntryModifyRequest request) {
        return ResponseEntity.ok(entryModifications.modifyEntries(ParticipantPrincipal.registrationId(), request));
    }
}
