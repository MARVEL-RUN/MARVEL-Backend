package kr.co.teambrain.marvelrun.admin.event.query.report;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

/** 실제 XLSX를 다시 읽어 시트 선택과 정수 합계·날짜 셀·문자열 안전성을 검증한다. */
class RegistrationDailyReportExcelWriterTest {
    /** 당일·누계 두 표의 각 행 합계가 독립적으로 출력된다. */
    @Test
    void writesBothSheetsWithNumericCounts() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new RegistrationDailyReportExcelWriter().write(report(),ReportExcelMode.BOTH,response);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheetName(0)).isEqualTo("당일");
            assertThat(workbook.getSheetName(1)).isEqualTo("누계");
            assertThat(workbook.getSheetAt(0).getRow(10).getCell(3).getNumericCellValue()).isEqualTo(10);
            assertThat(workbook.getSheetAt(0).getRow(13).getCell(3).getNumericCellValue()).isEqualTo(6);
            assertThat(workbook.getSheetAt(1).getRow(10).getCell(3).getNumericCellValue()).isEqualTo(100);
            assertThat(workbook.getSheetAt(1).getRow(13).getCell(3).getNumericCellValue()).isEqualTo(60);
            assertThat(workbook.getSheetAt(0).getRow(8).getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(DateUtil.isCellDateFormatted(workbook.getSheetAt(0).getRow(6).getCell(0))).isTrue();
            assertThat(workbook.getSheetAt(0).getRow(7).getCell(1).getCellType()).isEqualTo(CellType.STRING);
            assertThat(workbook.getSheetAt(0).getRow(7).getCell(1).getStringCellValue()).isEqualTo("=5km");
        }
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Content-Disposition")).contains("filename*=UTF-8''")
                .contains("filename=\"daily_report_");
    }

    /** query로 선택한 시트만 작성한다. */
    @Test
    void writesRequestedSheetOnly() throws Exception {
        for (ReportExcelMode mode : List.of(ReportExcelMode.DAILY,ReportExcelMode.CUMULATIVE)) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            new RegistrationDailyReportExcelWriter().write(report(),mode,response);
            try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
                assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
                assertThat(workbook.getSheetName(0)).isEqualTo(mode==ReportExcelMode.DAILY ? "당일" : "누계");
            }
        }
    }

    /** 신청자가 없고 코스가 없는 대회도 합계 0의 정상 파일을 만든다. */
    @Test
    void writesEmptyCourseReport() throws Exception {
        RegistrationDailyReport sample = report();
        RegistrationDailyReport empty = new RegistrationDailyReport(sample.eventId(),sample.eventName(),sample.openedAt(),
                sample.startDate(),sample.endDate(),sample.generatedAt(),List.of(),
                List.of(new RegistrationDailyReport.Day(sample.startDate(),List.of(),List.of())));
        MockHttpServletResponse response = new MockHttpServletResponse();
        new RegistrationDailyReportExcelWriter().write(empty,ReportExcelMode.BOTH,response);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(workbook.getSheetAt(0).getRow(13).getCell(1).getNumericCellValue()).isZero();
        }
    }

    /** 합계 검증에 사용할 독립적인 작은 집계 자료를 만든다. */
    private RegistrationDailyReport report() {
        LocalDate date = LocalDate.of(2026,9,6);
        return new RegistrationDailyReport("event","마블런",date.minusDays(5).atStartOfDay(),date,date,
                LocalDateTime.of(2026,9,7,9,0),
                List.of(new RegistrationDailyReport.Course("a","=5km"),new RegistrationDailyReport.Course("b","10km")),
                List.of(new RegistrationDailyReport.Day(date,
                        List.of(new RegistrationDailyReport.Counts(1,2,1,1),new RegistrationDailyReport.Counts(3,4,2,2)),
                        List.of(new RegistrationDailyReport.Counts(10,20,10,10),new RegistrationDailyReport.Counts(30,40,20,20)))));
    }
}
