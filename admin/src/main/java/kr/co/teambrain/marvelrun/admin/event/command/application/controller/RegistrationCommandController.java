package kr.co.teambrain.marvelrun.admin.event.command.application.controller;


import jakarta.validation.Valid;
import kr.co.teambrain.marvelrun.admin.common.dto.request.PasswordResetRequest;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.AdminRegistrationModifyRequest;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.RegistrationDeleteResponse;
import kr.co.teambrain.marvelrun.admin.event.command.application.service.RegistrationCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/v1/admin/registrations")
@RequiredArgsConstructor
public class RegistrationCommandController {

    private final RegistrationCommandService registrationCommandService;

    @PutMapping("/{registrationId}/password")
    public ResponseEntity<Void> resetPersonalPassword(
            @PathVariable String registrationId,
            @Valid @RequestBody PasswordResetRequest request
    ) {
        registrationCommandService.resetPersonalPassword(registrationId, request);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{registrationId}/basic-info")
    public ResponseEntity<Void> modifyRegistrationBasicInfo(
            @PathVariable String registrationId,
            @Valid @RequestBody AdminRegistrationModifyRequest request
    ) {
        registrationCommandService.modifyRegistrationBasicInfo(registrationId, request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{registrationId}")
    public ResponseEntity<RegistrationDeleteResponse> deletePaymentPendingRegistration(
            @PathVariable String registrationId
    ) {
        return ResponseEntity.ok(
                registrationCommandService.deletePaymentPendingRegistration(registrationId)
        );
    }
}
