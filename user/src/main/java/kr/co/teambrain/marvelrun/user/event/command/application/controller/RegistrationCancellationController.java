package kr.co.teambrain.marvelrun.user.event.command.application.controller;

import jakarta.validation.Valid;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationCancellationCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 개인 참가 취소와 단체 전체 취소 진입점이다. 대상·금액은 서버의 현재 DB로 결정한다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/public/events/{eventId}")
public class RegistrationCancellationController {
    private final RegistrationCancellationCommandService service;

    /** 개인 신청의 현재 본인확인 정보로 참가 취소를 요청한다. */
    @PostMapping("/registrations/{registrationId}/cancellation")
    public ResponseEntity<RegistrationModificationSettlementResult> personal(
            @PathVariable("eventId") String eventId,
            @PathVariable("registrationId") String registrationId,
            @Valid @RequestBody RegistrationAccessRequest access) {
        return ResponseEntity.ok(service.cancelPersonal(eventId, registrationId, access));
    }

    /** 인증된 단체의 현재 활성 구성원 전체를 취소한다. 일부 제거는 기존 수정 API를 사용한다. */
    @PostMapping("/organizations/{organizationId}/cancellation")
    public ResponseEntity<RegistrationModificationSettlementResult> organization(
            @PathVariable("eventId") String eventId,
            @PathVariable("organizationId") String organizationId,
            @Valid @RequestBody OrganizationAccessRequest access) {
        return ResponseEntity.ok(service.cancelOrganization(eventId, organizationId, access));
    }
}
