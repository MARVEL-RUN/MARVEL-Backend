package kr.co.teambrain.marvelrun.admin.event.command.application.controller;


import jakarta.validation.Valid;
import kr.co.teambrain.marvelrun.admin.common.dto.request.PasswordResetRequest;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.AdminRegistrationModifyRequest;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.RegistrationDeleteResponse;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.UnpaidRegistrationBatchRequest;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.UnpaidRegistrationBatchResponse;
import kr.co.teambrain.marvelrun.admin.event.command.application.service.RegistrationCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 관리자 신청 변경과 단건·대회별 미결제 신청 삭제 API를 제공한다. */
@RestController
@RequestMapping("/v1/admin")
@RequiredArgsConstructor
public class RegistrationCommandController {

    private final RegistrationCommandService registrationCommandService;

    /** 기존 개인 신청 비밀번호 초기화 경로를 유지한다. */
    @PutMapping("/registrations/{registrationId}/password")
    public ResponseEntity<Void> resetPersonalPassword(
            @PathVariable String registrationId,
            @Valid @RequestBody PasswordResetRequest request
    ) {
        registrationCommandService.resetPersonalPassword(registrationId, request);
        return ResponseEntity.noContent().build();
    }

    /** 기존 신청 기본 정보 수정 경로를 유지한다. */
    @PatchMapping("/registrations/{registrationId}/basic-info")
    public ResponseEntity<Void> modifyRegistrationBasicInfo(
            @PathVariable String registrationId,
            @Valid @RequestBody AdminRegistrationModifyRequest request
    ) {
        registrationCommandService.modifyRegistrationBasicInfo(registrationId, request);
        return ResponseEntity.noContent().build();
    }

    /** 기존 단건 미결제 신청 삭제 결과를 반환한다. */
    @DeleteMapping("/registrations/{registrationId}")
    public ResponseEntity<RegistrationDeleteResponse> deletePaymentPendingRegistration(
            @PathVariable String registrationId
    ) {
        return ResponseEntity.ok(
                registrationCommandService.deletePaymentPendingRegistration(registrationId)
        );
    }

    /** 같은 대회의 미결제 신청을 건별로 처리하고 성공·실패 목록을 반환한다. */
    @PostMapping("/events/{eventId}/registrations/unpaid-cancellations")
    public ResponseEntity<UnpaidRegistrationBatchResponse> cancelUnpaidRegistrations(
            @PathVariable("eventId") String eventId,
            @RequestBody(required = false) UnpaidRegistrationBatchRequest request
    ) {
        return ResponseEntity.ok(
                registrationCommandService.cancelUnpaidRegistrations(eventId, request)
        );
    }
}
