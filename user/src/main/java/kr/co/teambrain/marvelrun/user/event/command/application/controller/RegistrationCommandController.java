package kr.co.teambrain.marvelrun.user.event.command.application.controller;

import jakarta.validation.Valid;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.RegistrationCreateResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.service.RegistrationCommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/public/events")
public class RegistrationCommandController {

    private final RegistrationCommandService
            registrationCommandService;


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