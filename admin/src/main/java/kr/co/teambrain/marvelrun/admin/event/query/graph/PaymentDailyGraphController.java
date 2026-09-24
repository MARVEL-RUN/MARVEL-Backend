package kr.co.teambrain.marvelrun.admin.event.query.graph;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.Operation;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.query.report.RegistrationDailyReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
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
    private final RegistrationDailyReportService registrationDailyReportService;

    /** ISO 날짜로 조회 범위를 받고 기존 관리자 Bearer 인증을 적용한다. */
    @GetMapping("/{eventId}/daily-payment-graph")
    @Operation(
            summary = "일별 결제자 그래프 조회",
            description = "현재 유효 결제자를 최초 결제일 기준으로 일별 집계합니다. "
                    + "부분환불·추가결제 대상자는 결제자로 유지되며, "
                    + "취소·전액환불 시 과거 최초 결제일의 수치에서도 제외됩니다."
    )
    public PaymentDailyGraphResponse getDailyPaymentGraph(
            @PathVariable("eventId") String eventId,
            @RequestParam(value = "startDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate startDate,
            @RequestParam(value = "endDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate endDate
    ) {

        Event event =
                registrationDailyReportService.getReportEvent(eventId);

        return registrationDailyReportService.getPaymentDailyGraph(
                event,
                startDate,
                endDate
        );
    }
}
