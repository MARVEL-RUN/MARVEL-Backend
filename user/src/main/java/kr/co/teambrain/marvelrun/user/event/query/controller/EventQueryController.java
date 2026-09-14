package kr.co.teambrain.marvelrun.user.event.query.controller;


import kr.co.teambrain.marvelrun.user.event.query.dto.response.RegistrationOptionResponse;
import kr.co.teambrain.marvelrun.user.event.query.service.EventQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/public")
public class EventQueryController {

    private final EventQueryService eventQueryService;


    @GetMapping("/events/{eventId}/registration-options")
    public ResponseEntity<RegistrationOptionResponse> getRegistrationOptions(
            @PathVariable("eventId") String eventId
    ) {
        return ResponseEntity.ok(eventQueryService.getRegistrationOptions(eventId));
    }
}
