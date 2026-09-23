package kr.co.teambrain.marvelrun.admin.event.query.report;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/** 집계 결과만 받아 날짜별 일반·아동 코스 표를 XLSX로 작성한다. DB 조회는 수행하지 않는다. */
@Component
public class RegistrationDailyReportExcelWriter {
    private static final String[] LABELS = {
            "신청자(일반)","신청자(아동)","신청자(합계)",
            "결제자(일반)","결제자(아동)","결제자(합계)"
    };

    /** 행 메모리를 100개로 제한하고 임시파일은 성공·실패 모두 정리한다. */
    public void write(RegistrationDailyReport report, ReportExcelMode mode,
                      HttpServletResponse response) throws IOException {
        if (report.courses().size() > 16382) {
            throw new CustomException(ErrorCode.REPORT_CONFIGURATION_INVALID, " 엑셀 열 한도를 초과했습니다.");
        }
        SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        try (workbook) {
            workbook.setCompressTempFiles(true);
            Styles styles = styles(workbook);
            if (mode == ReportExcelMode.CUMULATIVE || mode == ReportExcelMode.BOTH) {
                sheet(workbook, report, true, styles);
            }

            if (mode == ReportExcelMode.DAILY || mode == ReportExcelMode.BOTH) {
                sheet(workbook, report, false, styles);
            }
            String suffix = report.startDate()+"_"+report.endDate()+"_"+mode.name()+".xlsx";
            String filename = URLEncoder.encode("마블런_일별집계_"+suffix,StandardCharsets.UTF_8)
                    .replace("+","%20");
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"daily_report_"+suffix+"\"; filename*=UTF-8''"+filename);
            response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
            response.setHeader(HttpHeaders.PRAGMA,"no-cache");
            response.setDateHeader(HttpHeaders.EXPIRES,0);
            response.setHeader("X-Content-Type-Options","nosniff");
            workbook.write(response.getOutputStream());
            response.getOutputStream().flush();
        } catch (CustomException exception) {
            /** 기존 업무 예외의 코드와 메시지는 보존한다. */
            throw exception;

        } catch (IOException | RuntimeException exception) {
            /** 파일 생성·POI 처리 실패의 원인은 서버 로그에 보존한다. */
//            log.error("일별 보고서 엑셀 생성 실패", exception);

            throw new CustomException(
                    ErrorCode.REPORT_EXCEL_GENERATION_FAILED
            );
        } finally {
            workbook.dispose();
        }
    }

    /** 당일과 누계는 동일한 코스·행 구성을 사용하며 각 날짜 표를 세로로 배치한다. */
    /**
     * 누계는 접수 시작부터 조회 종료일까지의 최종 표 하나를 출력한다.
     * 일별은 조회 기간의 날짜별 표를 세로로 배치한다.
     */
    private void sheet(
            SXSSFWorkbook workbook,
            RegistrationDailyReport report,
            boolean cumulative,
            Styles styles
    ) {
        String name = cumulative ? "누계" : "일별";
        Sheet sheet = workbook.createSheet(name);
        int lastColumn = report.courses().size() + 1;

        sheet.setDisplayGridlines(false);
        sheet.createFreezePane(1, 5);
        sheet.setColumnWidth(0, 23 * 256);

        for (int column = 1; column <= lastColumn; column++) {
            sheet.setColumnWidth(column, 20 * 256);
        }

        banner(
                sheet, 0, lastColumn,
                report.eventName() + " · 아동 유무별 " + name + " 집계",
                styles.title()
        );

        String period = cumulative
                ? "누계 기간: " + report.openedAt() + " ~ " + report.endDate() + " 종료까지"
                : "보고 기간: " + report.startDate() + " ~ " + report.endDate()
                + " / 접수 시작: " + report.openedAt();

        banner(sheet, 1, lastColumn, period, styles.note());

        banner(
                sheet, 2, lastColumn,
                "조회 기준(KST): " + report.generatedAt()
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                styles.note()
        );
        banner(
                sheet, 3, lastColumn,
                "현재 변경·취소·환불 상태 반영 / 과거 마감 확정본 아님",
                styles.note()
        );
        banner(
                sheet, 4, lastColumn,
                "신청자: 신청일 기준 / 결제자: 최초 결제일 기준 / 단위: 명",
                styles.note()
        );


        /** 누계는 마지막 날짜의 최종 누계만, 일별은 모든 날짜를 출력한다. */
        List<RegistrationDailyReport.Day> outputDays =
                cumulative && !report.days().isEmpty()
                        ? List.of(report.days().getLast())
                        : report.days();

        int rowIndex = 6;

        for (RegistrationDailyReport.Day day : outputDays) {
            Row dayRow = sheet.createRow(rowIndex++);
            dayRow.setHeightInPoints(26);

            Cell dateCell = dayRow.createCell(0);
            dateCell.setCellValue(day.date().atStartOfDay());
            dateCell.setCellStyle(styles.date());

            Cell titleCell = dayRow.createCell(1);
            titleCell.setCellValue(
                    cumulative
                            ? "접수 시작 ~ 조회 종료일 누계"
                            : "해당일 00:00 ~ 다음날 00:00 미만"
            );
            titleCell.setCellStyle(styles.header());

            if (lastColumn > 1) {
                sheet.addMergedRegion(
                        new CellRangeAddress(
                                dayRow.getRowNum(),
                                dayRow.getRowNum(),
                                1,
                                lastColumn
                        )
                );
            }

            Row header = sheet.createRow(rowIndex++);
            header.setHeightInPoints(32);

            text(header, 0, "구분", styles.header());

            for (int course = 0; course < report.courses().size(); course++) {
                text(
                        header,
                        course + 1,
                        report.courses().get(course).name(),
                        styles.header()
                );
            }

            text(header, lastColumn, "합계", styles.header());

            List<RegistrationDailyReport.Counts> values =
                    cumulative ? day.cumulative() : day.daily();

            for (int line = 0; line < LABELS.length; line++) {
                Row row = sheet.createRow(rowIndex++);
                row.setHeightInPoints(23);

                boolean total = line == 2 || line == 5;

                text(
                        row,
                        0,
                        LABELS[line],
                        total ? styles.total() : styles.label()
                );

                long sum = 0;

                for (int course = 0; course < values.size(); course++) {
                    long count = count(values.get(course), line);
                    sum += count;

                    number(
                            row,
                            course + 1,
                            count,
                            total ? styles.total() : styles.number()
                    );
                }

                number(row, lastColumn, sum, styles.total());
            }

            rowIndex++;
        }

        sheet.getPrintSetup().setLandscape(true);
        sheet.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
        sheet.getPrintSetup().setFitWidth((short) 1);
        sheet.getPrintSetup().setFitHeight((short) 0);
        sheet.setFitToPage(true);
        sheet.setRepeatingRows(new CellRangeAddress(0, 4, -1, -1));
    }

    /** 일반·아동과 신청·결제 구분에 해당하는 행 값을 계산한다. */
    private long count(RegistrationDailyReport.Counts value, int row) {
        return switch (row) {
            case 0 -> value.applicantGeneral();
            case 1 -> value.applicantChild();
            case 2 -> value.applicantGeneral()+value.applicantChild();
            case 3 -> value.paidGeneral();
            case 4 -> value.paidChild();
            case 5 -> value.paidGeneral()+value.paidChild();
            default -> throw new IllegalArgumentException("보고 행 범위 오류");
        };
    }

    /** 조회 기준과 제목은 표 너비로 병합하여 표시한다. */
    private void banner(Sheet sheet, int rowIndex, int lastColumn, String value, CellStyle style) {
        Row row = sheet.createRow(rowIndex);
        row.setHeightInPoints(rowIndex==0 ? 36 : 44);
        text(row,0,value,style);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex,rowIndex,0,lastColumn));
    }

    /** 문자열은 수식으로 실행하지 않고 XML 제어문자와 최대 길이를 정리한다. */
    private void text(Row row, int column, String value, CellStyle style) {
        String clean = value == null ? "" : value.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]","");
        Cell cell = row.createCell(column);
        cell.setCellValue(clean.length()>32767 ? clean.substring(0,32767) : clean);
        cell.setCellStyle(style);
    }

    /** 인원은 정수 표시의 숫자 셀로 저장한다. */
    private void number(Row row, int column, long value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    /** 스타일 수가 날짜 수에 비례하지 않도록 워크북마다 한 번만 생성한다. */
    private Styles styles(SXSSFWorkbook workbook) {
        Font normal = workbook.createFont();
        normal.setFontName("맑은 고딕"); normal.setFontHeightInPoints((short)11);
        Font bold = workbook.createFont();
        bold.setFontName("맑은 고딕"); bold.setFontHeightInPoints((short)11); bold.setBold(true);
        CellStyle label = workbook.createCellStyle();
        label.setFont(normal); label.setVerticalAlignment(VerticalAlignment.CENTER); label.setWrapText(true);
        label.setBorderBottom(BorderStyle.THIN); label.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        CellStyle number = workbook.createCellStyle(); number.cloneStyleFrom(label);
        number.setAlignment(HorizontalAlignment.RIGHT); number.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));
        CellStyle total = workbook.createCellStyle(); total.cloneStyleFrom(number); total.setFont(bold);
        total.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex()); total.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        CellStyle header = workbook.createCellStyle(); header.cloneStyleFrom(total); header.setAlignment(HorizontalAlignment.CENTER);
        CellStyle date = workbook.createCellStyle(); date.cloneStyleFrom(header);
        date.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
        CellStyle title = workbook.createCellStyle(); title.cloneStyleFrom(header); title.setAlignment(HorizontalAlignment.LEFT);
        CellStyle note = workbook.createCellStyle(); note.setFont(normal); note.setWrapText(true); note.setVerticalAlignment(VerticalAlignment.CENTER);
        return new Styles(label,number,total,header,date,title,note);
    }

    /** 동일 워크북에서만 공유하는 스타일 묶음이다. */
    private record Styles(CellStyle label, CellStyle number, CellStyle total, CellStyle header,
                          CellStyle date, CellStyle title, CellStyle note) { }
}
