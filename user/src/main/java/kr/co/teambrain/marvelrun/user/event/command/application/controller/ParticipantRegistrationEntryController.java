package kr.co.teambrain.marvelrun.user.event.command.application.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import kr.co.teambrain.marvelrun.user.common.security.util.ParticipantPrincipal;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationEntryModifyRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.RegistrationEntryListResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.RegistrationEntryModifyResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationEntryModificationService;
import kr.co.teambrain.marvelrun.user.participantauth.command.application.service.ParticipantOrderAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인한 주문자의 받는 사람 수정. 주문은 경로로 받고, 서비스가 잠금 뒤 토큰 주문자의 주문인지 본다.
 * 받는 사람 목록은 마이페이지 주문 상세(items[].entries[])에 있다.
 * me/entries는 프론트가 옮길 때까지 남긴다. 살아 있는 주문이 하나일 때만 쓸 수 있다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/participant/registrations")
@PreAuthorize("hasRole('PARTICIPANT')")
public class ParticipantRegistrationEntryController {

    private static final String MODIFY_DESCRIPTION = "상품(단품·패키지) 신청의 받는 사람 이름·연락처·생년월일·성별을 수정합니다. "
            + "entryId는 주문 상세의 items[].entries[]에서 읽습니다. "
            + "요청에 담은 참가 항목만 바뀌고 가격·정원·배번은 그대로입니다. 신청자 정보 수정과 같은 수정 기간 안에서만 할 수 있습니다. "
            + "보호자 정보는 신청에 저장된 값으로 검증합니다. 한 명이라도 거절되면 아무것도 바뀌지 않습니다.";

    private final RegistrationEntryModificationService entryModifications;
    private final ParticipantOrderAccess orderAccess;

    @Operation(summary = "받는 사람 수정", description = MODIFY_DESCRIPTION)
    @PatchMapping("/{registrationId}/entries")
    public ResponseEntity<RegistrationEntryModifyResponse> modify(@PathVariable("registrationId") String registrationId,
                                                                  @Valid @RequestBody RegistrationEntryModifyRequest request) {
        return ResponseEntity.ok(entryModifications.modifyEntries(registrationId, ParticipantPrincipal.ordererKey(), request));
    }

    @Operation(summary = "받는 사람 목록(이전 경로)", deprecated = true,
            description = "주문 상세의 items[].entries[]로 옮겨 주세요. 살아 있는 주문이 하나일 때만 쓸 수 있습니다.")
    @GetMapping("/me/entries")
    public ResponseEntity<RegistrationEntryListResponse> listMe() {
        String ordererKey = ParticipantPrincipal.ordererKey();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(entryModifications.findEntries(orderAccess.resolveMe(ordererKey), ordererKey));
    }

    @Operation(summary = "받는 사람 수정(이전 경로)", deprecated = true,
            description = "{registrationId}/entries로 옮겨 주세요. " + MODIFY_DESCRIPTION)
    @PatchMapping("/me/entries")
    public ResponseEntity<RegistrationEntryModifyResponse> modifyMe(@Valid @RequestBody RegistrationEntryModifyRequest request) {
        String ordererKey = ParticipantPrincipal.ordererKey();
        return ResponseEntity.ok(entryModifications.modifyEntries(orderAccess.resolveMe(ordererKey), ordererKey, request));
    }
}
