package kr.co.teambrain.marvelrun.admin.event.query.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationDeliveryExcelRequest;
import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationSearchCondition;
import kr.co.teambrain.marvelrun.admin.event.query.dto.report.RegistrationDailyReportExcelWriter;
import kr.co.teambrain.marvelrun.admin.event.query.dto.report.RegistrationExcelFileNames;
import kr.co.teambrain.marvelrun.admin.event.query.service.RegistrationDailyReportService;
import kr.co.teambrain.marvelrun.admin.event.query.service.RegistrationDeliveryExcelService;
import kr.co.teambrain.marvelrun.admin.event.query.service.RegistrationExcelService;
import kr.co.teambrain.marvelrun.admin.event.query.support.TemporaryExcelResource;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import lombok.RequiredArgsConstructor;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;


/**
 * 관리자 신청 목록·일별 집계·최초 승인일 기준 배송 명단을 엑셀로 다운로드한다.
 */
@RestController
@RequestMapping("/v1/admin/registrations")
@RequiredArgsConstructor
public class RegistrationExcelController {

    private final ServerTimeProvider serverTimeProvider;

    private final RegistrationExcelService registrationExcelService;

    /**
     * 최초 참가비 승인 기준 배송 명단을 두 단계로 생성한다.
     */
    private final RegistrationDeliveryExcelService registrationDeliveryExcelService;

    /**
     * 관리자 요청 한 번에 정상·불명확 개인·단체 명단을 함께 다운로드한다.
     */
    @Operation(summary = "최초 참가비 승인일 기준 배송 명단 엑셀 다운로드",
            description = "KST 시작 포함·종료 제외입니다. 개인/단체 정상·불명확 4개 시트를 반환합니다. "
                    + "승인일 확인 불가 대상은 기간 판정 불가 사유로 불명확명단에 포함합니다. UUID는 출력하지 않습니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "개인·단체 정상·불명확 XLSX 파일",
            content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    schema = @io.swagger.v3.oas.annotations.media.Schema(type = "string", format = "binary")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "필수 시각·형식·기간 오류",
            content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json",
                    schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = kr.co.teambrain.marvelrun.admin.common.exception.ErrorResponse.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "대회 없음",
            content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json",
                    schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = kr.co.teambrain.marvelrun.admin.common.exception.ErrorResponse.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "조회·파일 생성 실패",
            content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/json",
                    schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = kr.co.teambrain.marvelrun.admin.common.exception.ErrorResponse.class)))
    @GetMapping(value = "/{eventId}/delivery-list/excel/download")
    public ResponseEntity<Resource> downloadRegistrationDeliveryExcel(
            @PathVariable String eventId, @Valid @ModelAttribute @ParameterObject RegistrationDeliveryExcelRequest period,
            BindingResult bindingResult, HttpServletRequest request) {
        // 기존 API의 예외 계약은 건드리지 않고 이 요청의 바인딩 오류만 업무 오류로 변환한다.
        if (bindingResult.hasErrors()) {
            throw new CustomException(ErrorCode.DELIVERY_EXCEL_PERIOD_INVALID);
        }
        TemporaryExcelResource file = registrationDeliveryExcelService.createRegistrationDeliveryExcel(eventId, period);
        file.attachTo(request);

        // 파일 생성과 DB 조회가 끝난 뒤 동기 응답 변환기로 전송한다.
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(file.contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.getFilename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header(HttpHeaders.PRAGMA, "no-cache")
                .header("X-Content-Type-Options", "nosniff").body(file);
    }

    /**
     * 날짜별 신청·결제 인원을 조회한다.
     */
    private final RegistrationDailyReportService registrationDailyReportService;

    /**
     * 조회가 끝난 집계 결과를 엑셀 응답으로 작성한다.
     */
    private final RegistrationDailyReportExcelWriter registrationDailyReportExcelWriter;


    /**
     * 대회와 검색 조건에 해당하는 신청 목록 전체를 다운로드한다.
     */
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

    /**
     * 선택 ID가 있으면 삭제 여부와 관계없이 해당 신청을, 없으면 대회의 삭제되지 않은 신청을 다운로드한다.
     */
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

    /**
     * 기존 명단 다운로드와 같은 컨트롤러에서 날짜별 집계표를 제공한다.
     */
    @Operation(
            summary = "아동유무별 일별 신청·결제 집계 엑셀 다운로드",
            description = "기간 내 날짜별 코스·일반·아동 구분의 신청자와 결제자 수를 다운로드합니다. "
                    + "mode는 DAILY(당일), CUMULATIVE(누계), BOTH(모두)이며, "
                    + "날짜 생략 시 접수 시작일부터 어제까지 조회합니다. 현재 변경·취소·환불 상태를 반영하므로 실행 일자에 따라 변동치가 있을 수 있습니다.."
    )
    @GetMapping("/{eventId}/daily-report/excel/download")
    public void downloadDailyReport(
            @PathVariable("eventId") String eventId,
            @RequestParam(value = "startDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            HttpServletResponse response
    ) throws IOException {
        /** 대회를 한 번 조회하여 파일명 작성과 보고서 생성에 사용한다. */
        Event event = registrationDailyReportService.getReportEvent(eventId);

        String fileName = generateExcelFileName(
                event.getNameKr(),
                RegistrationExcelFileNames.PARENT_CHILD_PAYMENT_CONFIRMED
        );

        SXSSFWorkbook workbook =
                registrationDailyReportService.getDailyPaymenterExcelReport(
                        event,
                        startDate,
                        endDate
                );

        try (workbook) {
            configureExcelResponse(fileName, response);
            writeExcelResponse(workbook, response);
        } finally {
            workbook.dispose();
        }
    }

    /**
     * 선택 다운로드에 사용할 신청 식별자 목록이다.
     */
    public record SelectionRequest(List<String> registrationIds) {
    }

    /**
     * 대회명·파일 목적·생성 시각으로 다운로드 파일명을 생성한다.
     */
    private String generateExcelFileName(
            String eventName,
            RegistrationExcelFileNames purpose
    ) {
        String timestamp = serverTimeProvider.currentDateTime()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        return eventName
                + "_" + purpose.getFileName()
                + "_" + timestamp
                + ".xlsx";
    }


    /**
     * 파일명과 엑셀 다운로드 응답 헤더를 설정한다.
     */
    private void configureExcelResponse(
            String fileName,
            HttpServletResponse response
    ) {
        String encodedFileName = URLEncoder.encode(
                fileName,
                StandardCharsets.UTF_8
        ).replace("+", "%20");

        response.setContentType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        );
        response.setHeader(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"registration_report.xlsx\"; "
                        + "filename*=UTF-8''" + encodedFileName
        );
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setDateHeader(HttpHeaders.EXPIRES, 0);
        response.setHeader("X-Content-Type-Options", "nosniff");
    }

    /**
     * 워크북을 응답 스트림에 기록한다. 워크북 정리는 호출자가 담당한다.
     */
    private void writeExcelResponse(
            SXSSFWorkbook workbook,
            HttpServletResponse response
    ) throws IOException {
        workbook.write(response.getOutputStream());
        response.getOutputStream().flush();
    }
}
