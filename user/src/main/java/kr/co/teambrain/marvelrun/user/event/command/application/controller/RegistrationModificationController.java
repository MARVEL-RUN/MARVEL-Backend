package kr.co.teambrain.marvelrun.user.event.command.application.controller;

import jakarta.validation.Valid;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult.Order;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationModificationCommandService;
import kr.co.teambrain.marvelrun.user.payment.command.application.PaymentRetryPreparationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 기존 신청 수정 흐름과 주문 재준비를 공개 API에 연결하며 매 요청의 본인확인은 서비스가 수행한다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/public/events/{eventId}")
public class RegistrationModificationController {
    private final RegistrationModificationCommandService modifications;
    private final PaymentRetryPreparationService retries;

    /** 개인 수정 후보를 검증·저장하고 별도 환불 결과와 최대 한 건의 결제 주문을 반환한다. */
    @PatchMapping("/registrations/{registrationId}")
    public ResponseEntity<RegistrationModificationSettlementResult> personal(
            @PathVariable("eventId") String eventId, @PathVariable("registrationId") String registrationId,
            @Valid @RequestBody RegistrationModificationRequest request) {
        return ResponseEntity.ok(modifications.modifyPersonal(eventId, registrationId, request));
    }

    /** 요청의 최종 명단과 DB 기존 명단을 서버에서 비교하여 추가·수정·제거를 일괄 처리한다. */
    @PatchMapping("/organizations/{organizationId}/registrations")
    public ResponseEntity<RegistrationModificationSettlementResult> organization(
            @PathVariable("eventId") String eventId, @PathVariable("organizationId") String organizationId,
            @Valid @RequestBody OrgRegistrationModificationRequest request) {
        return ResponseEntity.ok(modifications.modifyOrganization(eventId, organizationId, request));
    }

    /** 개인의 실패 주문을 재준비한다. 실제 승인은 기존 공통 승인 API를 사용한다. */
    @PostMapping("/registrations/{registrationId}/payments/{paymentId}/retry")
    public ResponseEntity<Order> retryPersonal(
            @PathVariable("eventId") String eventId, @PathVariable("registrationId") String registrationId,
            @PathVariable("paymentId") String paymentId, @Valid @RequestBody RegistrationAccessRequest access) {
        return ResponseEntity.ok(retries.preparePersonal(eventId, registrationId, paymentId, access));
    }

    /** 최초·추가·혼합 결제를 구분하는 별도 API 없이 단체 주문을 한 번에 재준비한다. */
    @PostMapping("/organizations/{organizationId}/payments/{paymentId}/retry")
    public ResponseEntity<Order> retryOrganization(
            @PathVariable("eventId") String eventId, @PathVariable("organizationId") String organizationId,
            @PathVariable("paymentId") String paymentId, @Valid @RequestBody OrganizationAccessRequest access) {
        return ResponseEntity.ok(retries.prepareOrganization(eventId, organizationId, paymentId, access));
    }
}
