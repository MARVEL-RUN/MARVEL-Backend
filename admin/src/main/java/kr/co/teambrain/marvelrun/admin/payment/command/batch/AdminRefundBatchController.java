package kr.co.teambrain.marvelrun.admin.payment.command.batch;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import kr.co.teambrain.marvelrun.admin.security.details.CustomAdminDetail;
import kr.co.teambrain.marvelrun.admin.payment.command.dto.*;
import kr.co.teambrain.marvelrun.admin.payment.command.batch.AdminRefundBatchModels.*;

/* 명시적인 요청 DTO를 검증하고 관리자 인증 후 대상별 금융 처리 결과를 반환한다. */
@RestController
@RequestMapping("/v1/admin/events/{eventId}")
@RequiredArgsConstructor
public class AdminRefundBatchController {
    private final AdminRefundBatchService service;
    private final AdminRefundBatchStore store;

    /* 환불 요청 DTO를 검증하며 대상별 차단·성공 결과는 기존 HTTP 200 응답으로 반환한다. */
    @PostMapping("/payment-refunds")
    public ResponseEntity<Response> full(
            @PathVariable("eventId") String eventId,
            @Valid @RequestBody AdminPaymentRefundRequest request
    ) {
        // DTO 검증을 통과한 요청에 대해 관리자 인증을 확인한다.
        String adminId = adminId();

        // 배치 접수와 대상별 처리 결과의 응답 계약은 유지한다.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.full(eventId, adminId, request));
    }

    /* 변경 대상 DTO를 검증하고 환불·추가 납부·동일 금액 처리를 기존 서비스에 위임한다. */
    @PostMapping({"/payment-partial-refunds"})
    public ResponseEntity<Response> partial(
            @PathVariable("eventId") String eventId,
            @Valid @RequestBody AdminPaymentPartialRefundRequest request
    ) {
        // DTO 검증을 통과한 요청에 대해 관리자 인증을 확인한다.
        String adminId = adminId();

        // 서비스에서 기존 Jakarta Validation과 업무 검증을 수행한다.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.partial(eventId, adminId, request));
    }
    /** 최초 응답 유실 시 같은 requestId로 저장 결과만 확인한다. */
    @GetMapping("/payment-refund-results")
    public ResponseEntity<Response> byRequest(@PathVariable("eventId") String eventId,
            @RequestParam("requestId") String requestId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.byRequest(eventId, requestId, adminId()));
    }
    /** 이벤트 소속을 검증한 배치 진행 요약이다. */
    @GetMapping("/payment-refund-batches/{batchId}")
    public ResponseEntity<Summary> summary(@PathVariable("eventId") String eventId,@PathVariable("batchId") String batchId) {
        adminId(); return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.summary(eventId,batchId));
    }
    /** 예외 필터와 페이지 크기 제한을 적용한 대상별 결과다. */
    @GetMapping("/payment-refund-batches/{batchId}/items")
    public ResponseEntity<Items> items(@PathVariable("eventId") String eventId,@PathVariable("batchId") String batchId,
            @RequestParam(value="page",defaultValue="0") int page,@RequestParam(value="size",defaultValue="20") int size,
            @RequestParam(value="exceptionsOnly",defaultValue="false") boolean exceptionsOnly) {
        adminId(); return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.items(eventId,batchId,page,size,exceptionsOnly));
    }
    /** 일반 사용자 Principal이나 익명 인증으로 관리자 환불을 요청할 수 없다. */
    private String adminId() {
        Authentication auth=SecurityContextHolder.getContext().getAuthentication();
        if (auth==null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof CustomAdminDetail detail)) {
            throw new AccessDeniedException("관리자 인증이 필요합니다.");
        }
        return detail.getUsername();
    }
}
