package kr.co.teambrain.marvelrun.admin.payment.command.batch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import kr.co.teambrain.marvelrun.admin.security.details.CustomAdminDetail;
import kr.co.teambrain.marvelrun.admin.payment.command.dto.*;
import kr.co.teambrain.marvelrun.admin.payment.command.batch.AdminRefundBatchModels.*;

/** 결제액 환불과 부분환불의 진입점을 분리하고 기존 관리자 인증을 사용한다. */
@RestController
@RequestMapping("/v1/admin/events/{eventId}")
@RequiredArgsConstructor
public class AdminRefundBatchController {
    private final AdminRefundBatchService service;
    private final AdminRefundBatchStore store;
    private final ObjectMapper mapper;

    /** 동기 처리 결과를 200으로 반환한다. HTTP 성공과 대상별 환불 성공은 다르다. */
    @PostMapping("/payment-refunds")
    public ResponseEntity<Response> full(
            @PathVariable("eventId") String eventId,
            @Valid @RequestBody AdminPaymentRefundRequest request
    ) {
        String adminId = adminId();

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.full(eventId, adminId, request));
    }

    /** 신청 정보 변경에 따른 환불·추가 납부·동일 금액 처리를 수행한다. */
    @PostMapping({"/payment-partial-refunds"})
    public ResponseEntity<Response> partial(
            @PathVariable("eventId") String eventId,
            @Valid @RequestBody AdminPaymentPartialRefundRequest request
    ) {
        String adminId = adminId();

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
    /** 수기 금액 등 알 수 없는 입력을 HTTP 400으로 반환한다. */
    @ExceptionHandler(JsonProcessingException.class)
    public ResponseEntity<Map<String,String>> invalidJson(JsonProcessingException error) {
        return ResponseEntity.badRequest().body(Map.of("code","INVALID_REFUND_REQUEST","message","허용되지 않는 필드 또는 잘못된 요청 형식입니다."));
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
