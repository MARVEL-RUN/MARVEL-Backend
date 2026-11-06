package kr.co.teambrain.marvelrun.user.event.command.application.controller;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import kr.co.teambrain.marvelrun.user.common.security.filter.PublicRegistrationAccess;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.PersonalPasswordChangeRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationPasswordChangeService;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.RegistrationCreateResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 개인 신청 생성과 기존 비밀번호 검증을 통한 비밀번호 변경 요청을 처리한다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/public/events")
public class RegistrationCommandController {

    private final RegistrationCommandService
            registrationCommandService;

    private final RegistrationPasswordChangeService passwordChangeService;

    /** 개인 신청의 비밀번호를 변경하고 성공 시 본문 없이 응답한다. */
    @PublicRegistrationAccess
    @PatchMapping("/{eventId}/registrations/{registrationId}/password")
    public ResponseEntity<Void> changePassword(
            @PathVariable("eventId") String eventId,
            @PathVariable("registrationId") String registrationId,
            @Valid @RequestBody PersonalPasswordChangeRequest request
    ) {
        passwordChangeService.changePersonal(eventId, registrationId, request);

        return ResponseEntity.noContent().build();
    }


    /** 개인 신청 정보와 선택한 그룹을 검증하여 접수한다. */
    @Operation(summary = "개인 신청", description = "참가자의 인적사항, 종목·기념품·그룹 선택 및 약관 동의 정보를 받아 개인 참가신청을 생성합니다. 신청 결과와 결제 진행에 필요한 정보를 반환합니다. "
            + "단품·패키지 상품은 items에 상품마다 한 줄씩(최대 10줄) 상품·기념품·받는 사람(entries)을 담고 최상위 eventCategoryId·selectedSouvenirList·entries는 비웁니다. "
            + "단품 줄은 그 종목의 받는 사람 1명, 패키지 줄은 하위 종목마다 1명입니다. 같은 상품을 여러 번 담을 수 있습니다. "
            + "같은 신청자 주문 여러 건을 허용한 대회에서 이미 신청한 사람이면 409 REGISTRATION_ADDITIONAL_ORDER_LOGIN_REQUIRED를 돌려줍니다.")
    @PostMapping("/{eventId}/registrations")
    public ResponseEntity<RegistrationCreateResponse> register(
            @PathVariable String eventId,
            @Valid
            @RequestBody RegistrationCreateRequest request
    ) {

        RegistrationCreateResponse response =
                registrationCommandService
                        .register(
                                eventId,
                                request
                        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }
}
