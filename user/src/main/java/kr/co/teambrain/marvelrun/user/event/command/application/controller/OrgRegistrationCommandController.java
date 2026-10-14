package kr.co.teambrain.marvelrun.user.event.command.application.controller;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.OrgRegistrationCreateResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationPasswordChangeRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationPasswordChangeService;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.service.OrgRegistrationCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 단체 참가신청 생성·중복 확인과 단체 계정 비밀번호 변경 API.
 *
 * 생성 책임:
 * - Organization 1건
 * - Registration N건
 * - Organization을 결제 대상으로 하는 Payment 1건
 *
 * 실제 Toss 승인(confirm)은
 * POST /public/payments/confirm
 * 의 공통 결제 흐름에서 처리한다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/public/events/{eventId}")
public class OrgRegistrationCommandController {

    private final OrgRegistrationCommandService
            orgRegistrationCommandService;

    private final RegistrationPasswordChangeService passwordChangeService;

    /** 단체 계정의 비밀번호를 변경하고 성공 시 본문 없이 응답한다. */
    @PatchMapping("/organizations/{organizationId}/password")
    public ResponseEntity<Void> changePassword(
            @PathVariable("eventId") String eventId,
            @PathVariable("organizationId") String organizationId,
            @Valid @RequestBody OrganizationPasswordChangeRequest request
    ) {
        passwordChangeService.changeOrganization(eventId, organizationId, request);

        return ResponseEntity.noContent().build();
    }


    /**
     * 단체 신청 생성 + Organization 대상 Payment READY 생성.
     *
     * 최종 외부 경로:
     * POST /api/public/events/{eventId}/registrations/organization
     *
     * 주의:
     * /api prefix는 Edge Nginx/Spring context 경로에서 붙고,
     * Controller 자체에는 /api를 중복 선언하지 않는다.
     */
    @PostMapping("/registrations/organization")
    @Operation(summary = "단체 신청", description = "참가자마다 그룹 선택 또는 미선택을 전달하여 접수합니다.")
    public ResponseEntity<OrgRegistrationCreateResponse> registerOrganization(
            @PathVariable String eventId,
            @Valid @RequestBody OrgRegistrationCreateRequest request
    ) {

        OrgRegistrationCreateResponse response =
                orgRegistrationCommandService.register(
                        eventId,
                        request
                );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/registrations/organization/duplicate-id-check")
    public ResponseEntity<?> duplicateIdCheck(
            @PathVariable String eventId,
            @RequestParam("groupLoginId") String loginId
    ) {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(orgRegistrationCommandService.checkExistsLoginId(loginId, eventId));
    }

    @GetMapping("/registrations/organization/duplicate-name-check")
    public ResponseEntity<?> duplicateNameCheck(
            @PathVariable String eventId,
            @RequestParam("groupName") String groupName
    ) {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(orgRegistrationCommandService.checkExistsGroupName(groupName, eventId));
    }
}
