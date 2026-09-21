package kr.co.teambrain.marvelrun.user.event.query.controller;

import jakarta.validation.Valid;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.query.dto.RegistrationReceiptResponse;
import kr.co.teambrain.marvelrun.user.event.query.service.RegistrationReceiptQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 민감한 본인확인 정보는 POST 본문으로 받고 조회만 수행한다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/public/events/{eventId}")
public class RegistrationReceiptQueryController {
    private final RegistrationReceiptQueryService service;

    /** 개인의 접수 확인 카드를 반환한다. 신규 신청이나 결제를 만들지 않는다. */
    @PostMapping("/registrations/lookup")
    public ResponseEntity<List<RegistrationReceiptResponse>> personal(
            @PathVariable("eventId") String eventId,
            @Valid @RequestBody RegistrationAccessRequest access) {
        return noStore(service.personal(eventId, access));
    }

    /** 단체의 접수 확인 카드와 단체 단위 결제 안내를 반환한다. */
    @PostMapping("/organizations/lookup")
    public ResponseEntity<List<RegistrationReceiptResponse>> organization(
            @PathVariable("eventId") String eventId,
            @Valid @RequestBody OrganizationAccessRequest access) {
        return noStore(service.organization(eventId, access));
    }

    /** 개인정보·금융 상태를 브라우저와 중간 캐시에 저장하지 않는다. */
    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache").body(body);
    }
}
