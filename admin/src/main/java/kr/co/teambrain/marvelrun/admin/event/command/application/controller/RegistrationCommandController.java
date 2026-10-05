package kr.co.teambrain.marvelrun.admin.event.command.application.controller;


import jakarta.validation.Valid;
import kr.co.teambrain.marvelrun.admin.common.dto.request.PasswordResetRequest;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.AdminRegistrationModifyRequest;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.RegistrationDeleteResponse;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.UnpaidRegistrationBatchRequest;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.UnpaidRegistrationBatchResponse;
import kr.co.teambrain.marvelrun.admin.event.command.application.service.RegistrationCommandService;
import kr.co.teambrain.marvelrun.admin.event.command.application.service.OfflineRegistrationImportService;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.OfflineRegistrationImportResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.MediaType;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.multipart.MultipartFile;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 관리자 신청 변경과 단건·대회별 미결제 신청 삭제 API를 제공한다. */
@RestController
@RequestMapping("/v1/admin")
@RequiredArgsConstructor
public class RegistrationCommandController {

    private final RegistrationCommandService registrationCommandService;

    private final OfflineRegistrationImportService offlineRegistrationImportService;

    /** 관리자 제출 V3 파일을 외부 결제 완료 개인 신청으로 전체 저장하거나 행별 오류를 반환한다. */
    @Operation(summary = "외부 결제 개인 신청 V3 엑셀 업로드",
            description = "KST 승인일자와 HHmmss를 결합합니다. 한 행이라도 오류가 있으면 전체 저장을 취소합니다. 토스 통신은 하지 않습니다.")
    @PostMapping(value = "/events/{eventId}/registrations/offline-payments/import",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<OfflineRegistrationImportResponse> importOfflinePaidRegistrations(
            @PathVariable("eventId") String eventId,
            @RequestParam("paymentDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate paymentDate,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(offlineRegistrationImportService.importOfflinePaidRegistrations(eventId, paymentDate, file));
    }

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
