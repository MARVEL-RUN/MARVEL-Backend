package kr.co.teambrain.marvelrun.admin.payment.command.evidence;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import kr.co.teambrain.marvelrun.admin.security.details.CustomAdminDetail;
import kr.co.teambrain.marvelrun.admin.payment.command.evidence.AdminRefundEvidenceModels.*;

/** 관리자 인증을 거친 외부 증거 수집과 저장 이력 조회를 분리한다. */
@RestController
@RequestMapping("/v1/admin/events/{eventId}/payment-refunds/{paymentCancelId}/evidence")
@RequiredArgsConstructor
public class AdminRefundEvidenceController {
    private final AdminRefundEvidenceService service;
    private final AdminRefundEvidenceStore store;
    /** 서버에는 새 증거를 생성하므로 POST지만 외부 토스에는 GET만 전송한다. */
    @PostMapping
    public ResponseEntity<Evidence> check(@PathVariable("eventId") String eventId,
            @PathVariable("paymentCancelId") String cancelId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.check(eventId,cancelId,adminId()));
    }
    /** GET은 저장 이력만 읽으며 외부 호출·금융 변경을 하지 않는다. */
    @GetMapping
    public ResponseEntity<Page> list(@PathVariable("eventId") String eventId,
            @PathVariable("paymentCancelId") String cancelId,
            @RequestParam(value="page",defaultValue="0") int page,
            @RequestParam(value="size",defaultValue="20") int size) {
        adminId();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.list(eventId,cancelId,page,size));
    }
    /** 기존 관리자 진입점과 같은 인증 principal을 요구한다. */
    private String adminId() {
        Authentication auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof CustomAdminDetail detail)) {
            throw new AccessDeniedException("관리자 인증이 필요합니다.");
        }
        return detail.getUsername();
    }
}
