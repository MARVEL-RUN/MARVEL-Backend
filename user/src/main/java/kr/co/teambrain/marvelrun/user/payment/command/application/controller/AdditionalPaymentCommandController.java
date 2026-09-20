package kr.co.teambrain.marvelrun.user.payment.command.application.controller;

import jakarta.validation.Valid;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.payment.command.application.AdditionalPaymentPreparationService;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.AdditionalPaymentPrepareResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 현재 본인확인 정보를 받아 추가 결제 주문만 준비한다. 승인은 기존 결제 승인 API를 사용한다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/public/events/{eventId}")
public class AdditionalPaymentCommandController {
    private final AdditionalPaymentPreparationService service;

    /** 개인 추가 주문을 생성하거나 같은 유효 주문을 반환한다. */
    @PostMapping("/registrations/{registrationId}/payments/additional")
    public ResponseEntity<AdditionalPaymentPrepareResponse> personal(
            @PathVariable String eventId, @PathVariable String registrationId,
            @Valid @RequestBody RegistrationAccessRequest access) {
        return ResponseEntity.ok(service.preparePersonal(eventId, registrationId, access));
    }

    /** 단체의 기존 확정 구성원 추가 납부액을 하나의 주문으로 준비한다. */
    @PostMapping("/organizations/{organizationId}/payments/additional")
    public ResponseEntity<AdditionalPaymentPrepareResponse> organization(
            @PathVariable String eventId, @PathVariable String organizationId,
            @Valid @RequestBody OrganizationAccessRequest access) {
        return ResponseEntity.ok(service.prepareOrganization(eventId, organizationId, access));
    }
}