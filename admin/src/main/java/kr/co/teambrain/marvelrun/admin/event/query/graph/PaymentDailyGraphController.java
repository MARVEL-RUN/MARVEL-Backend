package kr.co.teambrain.marvelrun.admin.event.query.graph;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 운영 홈에 날짜별 최초 결제 참가자 수를 제공한다. */
@RestController
@RequestMapping("/v1/admin/registrations")
@RequiredArgsConstructor
public class PaymentDailyGraphController {
    private final PaymentDailyGraphService paymentGraphService;

    /** ISO 날짜로 조회 범위를 받고 기존 관리자 Bearer 인증을 적용한다. */
    @GetMapping("/{eventId}/graph/payment-daily")
    @Operation(
            summary = "일별 결제자 그래프 조회",
            description = "최초 결제일별 현재 유효 결제자 수와 누계를 조회합니다. 추가결제는 중복 집계하지 않으며, 취소·전액환불 시 과거 수치에도 반영됩니다.")
    public PaymentDailyGraphResponse get(
            @PathVariable("eventId") String eventId,
            @RequestParam(value = "startDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return paymentGraphService.getPaymentDailyGraph(eventId, startDate, endDate);
    }
}
