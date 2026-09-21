package kr.co.teambrain.marvelrun.admin.payment.query;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.teambrain.marvelrun.admin.payment.query.AdminPaymentQueryResponse.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 개인·단체 금융 이력과 주문 로그를 읽는다. 신청 목록은 별도 기능에서 제공한다. */
@Tag(name = "관리자 결제 조회", description = "신청 ID 또는 단체 ID의 결제·환불 내역과 주문 처리 로그를 조회합니다.")
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/admin/events/{eventId}")
public class AdminPaymentQueryController {
    private final AdminPaymentQueryService service;

    /** 개인의 모든 주문 상태와 금융 귀속을 조회한다. */
    @Operation(summary = "개인 결제·환불 내역", description = "실패·무효 주문과 취소 이력을 포함합니다. 단체원은 단체 금융 API를 사용합니다.")
    @GetMapping("/registrations/{registrationId}/payments")
    public ResponseEntity<Finance> personalPayments(
            @PathVariable("eventId") String eventId,
            @PathVariable("registrationId") String registrationId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return noStore(service.personalPayments(eventId, registrationId, page, size));
    }

    /** 제거된 구성원의 귀속도 포함하여 단체 주문을 확인한다. */
    @Operation(summary = "단체 결제·환불 내역", description = "주문 전체 금액, 구성원 귀속액, 취소 귀속을 반환하며 현재 명단 제외 여부도 표시합니다.")
    @GetMapping("/organizations/{organizationId}/payments")
    public ResponseEntity<Finance> organizationPayments(
            @PathVariable("eventId") String eventId,
            @PathVariable("organizationId") String organizationId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return noStore(service.organizationPayments(eventId, organizationId, page, size));
    }

    /** 주문 단위의 저장 로그만 읽으며 Toss에 요청하지 않는다. */
    @Operation(summary = "결제 처리 로그", description = "주문 및 연결된 취소 로그를 반환합니다. 내부 ID·키를 제외한 기록 시각, 처리 유형, 오류, metadata를 제공합니다.")
    @GetMapping("/payments/{paymentId}/logs")
    public ResponseEntity<Page<Log>> logs(
            @PathVariable("eventId") String eventId,
            @PathVariable("paymentId") String paymentId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return noStore(service.logs(eventId, paymentId, page, size));
    }

    /** 개인정보와 금융 기록의 HTTP 캐시 저장을 차단한다. */
    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache").body(body);
    }
}
