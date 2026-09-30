package kr.co.teambrain.marvelrun.user.event.command.application.controller;

import jakarta.validation.Valid;
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
    @PatchMapping("/{eventId}/registrations/{registrationId}/password")
    public ResponseEntity<Void> changePassword(
            @PathVariable("eventId") String eventId,
            @PathVariable("registrationId") String registrationId,
            @Valid @RequestBody PersonalPasswordChangeRequest request
    ) {
        passwordChangeService.changePersonal(eventId, registrationId, request);

        return ResponseEntity.noContent().build();
    }


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
