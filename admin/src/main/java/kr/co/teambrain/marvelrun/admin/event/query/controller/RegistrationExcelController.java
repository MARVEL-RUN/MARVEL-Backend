package kr.co.teambrain.marvelrun.admin.event.query.controller;

import jakarta.servlet.http.HttpServletResponse;
import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationSearchCondition;
import kr.co.teambrain.marvelrun.admin.event.query.service.RegistrationExcelService;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import kr.co.teambrain.marvelrun.admin.event.query.report.RegistrationDailyReport;
import kr.co.teambrain.marvelrun.admin.event.query.report.RegistrationDailyReportService;
import kr.co.teambrain.marvelrun.admin.event.query.report.RegistrationDailyReportExcelWriter;
import kr.co.teambrain.marvelrun.admin.event.query.report.ReportExcelMode;


/** 관리자 신청 목록의 전체·검색 결과·선택 항목을 엑셀로 다운로드한다. */
@RestController
@RequestMapping("/v1/admin/registrations")
@RequiredArgsConstructor
public class RegistrationExcelController {

    private final RegistrationExcelService registrationExcelService;

    /** 날짜별 신청·결제 인원을 조회한다. */
    private final RegistrationDailyReportService registrationDailyReportService;

    /** 조회가 끝난 집계 결과를 엑셀 응답으로 작성한다. */
    private final RegistrationDailyReportExcelWriter registrationDailyReportExcelWriter;


    /** 대회와 검색 조건에 해당하는 신청 목록 전체를 다운로드한다. */
    @GetMapping("/excel/download")
    public void download(
            @RequestParam("eventId") String eventId,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "eventCategoryId", required = false) String eventCategoryId,
            @RequestParam(value = "status", required = false) RegistrationStatus status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "organizationId", required = false) String organizationId,
            HttpServletResponse response
    ) throws IOException {
        registrationExcelService.download(
                new RegistrationSearchCondition(eventId, type, eventCategoryId, status, keyword),
                organizationId, null, response
        );
    }

    /** 선택 ID가 있으면 해당 항목만, 없으면 해당 대회의 전체 목록을 다운로드한다. */
    @PostMapping("/excel/download")
    public void downloadSelected(
            @RequestParam("eventId") String eventId,
            @RequestBody(required = false) SelectionRequest request,
            HttpServletResponse response
    ) throws IOException {
        registrationExcelService.download(
                new RegistrationSearchCondition(eventId, null, null, null, null),
                null, request == null ? null : request.registrationIds(), response
        );
    }

    /** 기존 명단 다운로드와 같은 컨트롤러에서 날짜별 집계표를 제공한다. */
    @Operation(
            summary = "일별 신청·결제 집계 엑셀 다운로드",
            description = "기간 내 날짜별 코스·일반·아동 구분의 신청자와 결제자 수를 다운로드합니다. "
                    + "mode는 DAILY(당일), CUMULATIVE(누계), BOTH(모두)이며, "
                    + "날짜 생략 시 접수 시작일부터 어제까지 조회합니다. 현재 변경·취소·환불 상태를 반영합니다."
    )
    @GetMapping("/{eventId}/daily-report/excel/download")
    public void downloadDailyReport(
            @PathVariable("eventId") String eventId,
            @RequestParam(value = "startDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(value = "mode", defaultValue = "BOTH") ReportExcelMode mode,
            HttpServletResponse response
    ) throws IOException {
        RegistrationDailyReport report = registrationDailyReportService.getDailyPaymenterReport(eventId, startDate, endDate);
        registrationDailyReportExcelWriter.write(report, mode, response);
    }

    /** 선택 다운로드에 사용할 신청 식별자 목록이다. */
    public record SelectionRequest(List<String> registrationIds) {
    }
}
