package kr.co.teambrain.marvelrun.admin.user.command.application.controller;

import jakarta.validation.Valid;
import kr.co.teambrain.marvelrun.admin.common.dto.request.LoginIdResetRequest;
import kr.co.teambrain.marvelrun.admin.common.dto.request.PasswordResetRequest;
import kr.co.teambrain.marvelrun.admin.user.command.application.service.OrganizationCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/v1/admin/organizations")
@RequiredArgsConstructor
public class OrganizationCommandController {

    private final OrganizationCommandService organizationCommandService;

    @PutMapping("/{organizationId}/password")
    public ResponseEntity<Void> resetOrganizationPassword(
            @PathVariable String organizationId,
            @Valid @RequestBody PasswordResetRequest request
    ) {
        organizationCommandService.resetOrganizationPassword(organizationId, request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{organizationId}/loginId")
    public ResponseEntity<?> resetOrganizationLoginId(
            @PathVariable String organizationId,
            @Valid @RequestBody LoginIdResetRequest request
    ) {
        organizationCommandService.resetOrganizationLoginId(request.newLoginId(), organizationId);
        return ResponseEntity.noContent().build();
    }



    @GetMapping("/organization/duplicate-id-check")
    public ResponseEntity<?> registerOrganization(
            @RequestParam("eventId") String eventId,
            @RequestParam("groupLoginId") String loginId
    ) {
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(organizationCommandService.checkExistsGroupInfo(loginId, eventId));
    }



}
