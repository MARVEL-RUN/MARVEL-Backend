package kr.co.teambrain.marvelrun.user.event.command.application.controller;

import jakarta.validation.Valid;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.service.OrgRegistrationCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PG 심사용 단체 참가신청 생성 API.
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
@RequestMapping("/public/events/{eventId}/registrations")
public class OrgRegistrationCommandController {

    private final OrgRegistrationCommandService
            orgRegistrationCommandService;


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
    @PostMapping("/organization")
    public ResponseEntity<?> registerOrganization(
            @PathVariable String eventId,
            @Valid @RequestBody OrgRegistrationCreateRequest request
    ) {

        Object response =
                orgRegistrationCommandService.register(
                        eventId,
                        request
                );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }
}
