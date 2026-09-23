package kr.co.teambrain.marvelrun.user.event.command.application.controller;

import jakarta.validation.Valid;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult.Order;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.payment.command.application.AdditionalPaymentPreparationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 관리자 수정 뒤 주문이 아직 없는 추가 납부를 기존 본인확인으로 준비한다. */
@RestController
@RequestMapping("/v1/public/events/{eventId}")
@RequiredArgsConstructor
public class AdditionalPaymentController {
    private final AdditionalPaymentPreparationService service;

    /** 개인 추가금 주문을 생성/재사용한다. 실제 승인은 기존 공통 승인 API로 진행한다. */
    @PostMapping("/registrations/{registrationId}/payments/additional/prepare")
    public ResponseEntity<Order> personal(@PathVariable("eventId") String eventId,
            @PathVariable("registrationId") String registrationId, @Valid @RequestBody RegistrationAccessRequest access) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.preparePersonal(eventId, registrationId, access));
    }

    /** 단체장의 본인확인 후 확정 구성원의 추가금만 한 주문으로 준비한다. */
    @PostMapping("/organizations/{organizationId}/payments/additional/prepare")
    public ResponseEntity<Order> organization(@PathVariable("eventId") String eventId,
            @PathVariable("organizationId") String organizationId, @Valid @RequestBody OrganizationAccessRequest access) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.prepareOrganization(eventId, organizationId, access));
    }
}
