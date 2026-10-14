package kr.co.teambrain.marvelrun.user.event.command.application.controller;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationStartGroupModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationStartGroupModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationStartGroupModificationService;
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
    private final RegistrationStartGroupModificationService startGroups;

    /** 개인 비밀번호로 인증하여 그룹 두 필드만 변경한다. */
    @Operation(summary = "개인 신청 그룹 전용 수정", description = "개인 신청 비밀번호로 본인확인 후 해당 신청의 그룹 선택 또는 미선택 상태만 변경합니다. 일반 신청 수정의 기간 제한과 외부결제 제한을 적용하지 않습니다.")
    @PatchMapping("/registrations/{registrationId}/start-group")
    public ResponseEntity<Void> modifyPersonalStartGroup(
            @PathVariable("eventId") String eventId, @PathVariable("registrationId") String registrationId,
            @Valid @RequestBody RegistrationStartGroupModificationRequest request) {
        startGroups.modifyPersonalRegistrationStartGroup(eventId, registrationId, request);
        return ResponseEntity.noContent().build();
    }

    /** 단체 계정으로 인증하여 전달한 참가자들의 그룹을 한 번에 변경한다. */
    @Operation(summary = "단체 참가자 그룹 전용 수정", description = "단체 비밀번호로 본인확인 후 전달된 참가자들의 그룹 선택 또는 미선택 상태만 일괄 변경하며, 요청에 없는 참가자는 유지합니다. 일반 신청 수정의 기간 제한과 외부결제 제한을 적용하지 않습니다.")
    @PatchMapping("/organizations/{organizationId}/registrations/start-groups")
    public ResponseEntity<Void> modifyOrganizationStartGroups(
            @PathVariable("eventId") String eventId, @PathVariable("organizationId") String organizationId,
            @Valid @RequestBody OrgRegistrationStartGroupModificationRequest request) {
        startGroups.modifyOrganizationRegistrationStartGroups(eventId, organizationId, request);
        return ResponseEntity.noContent().build();
    }

    /** 개인 수정 후보를 검증·저장하고 별도 환불 결과와 최대 한 건의 결제 주문을 반환한다. */
    @PatchMapping("/registrations/{registrationId}")
    @Operation(summary = "개인 신청 수정", description = "본인확인 후 개인 신청의 인적사항과 종목·기념품·그룹 등 신청 정보를 수정합니다. 변경에 따른 정산 결과와 필요한 결제 주문 정보를 반환합니다.")
    public ResponseEntity<RegistrationModificationSettlementResult> personal(
            @PathVariable("eventId") String eventId, @PathVariable("registrationId") String registrationId,
            @Valid @RequestBody RegistrationModificationRequest request) {
        return ResponseEntity.ok(modifications.modifyPersonalRegistration(eventId, registrationId, request));
    }

    /** 요청의 최종 명단과 DB 기존 명단을 서버에서 비교하여 추가·수정·제거를 일괄 처리한다. */
    @PatchMapping("/organizations/{organizationId}/registrations")
    @Operation(summary = "단체 신청 수정", description = "단체 본인확인 후 공통정보를 수정하고, 전달된 최종 명단을 기준으로 참가자를 추가·수정·제거합니다. 변경에 따른 정산 결과와 필요한 결제 주문 정보를 반환합니다.")
    public ResponseEntity<RegistrationModificationSettlementResult> organization(
            @PathVariable("eventId") String eventId, @PathVariable("organizationId") String organizationId,
            @Valid @RequestBody OrgRegistrationModificationRequest request) {
        return ResponseEntity.ok(modifications.modifyOrganizationRegistration(eventId, organizationId, request));
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
