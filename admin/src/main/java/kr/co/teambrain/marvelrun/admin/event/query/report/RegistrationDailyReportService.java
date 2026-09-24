package kr.co.teambrain.marvelrun.admin.event.query.report;

import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationStatDto;
import kr.co.teambrain.marvelrun.admin.event.query.repository.EventCategoryQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.EventQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.service.RegistrationQueryService;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 날짜 범위와 코스 순서를 검증하고 일별 집계로 당일·누계 표를 구성한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RegistrationDailyReportService {
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Seoul");
    private final EventCategoryQueryRepository categories;

    private final EventCommandRepository eventCommandRepository;
    private final RegistrationQueryRepository registrationQueryRepository;

    public SXSSFWorkbook getDailyPaymenterExcelReport(
            Event event,
            LocalDate startDate,
            LocalDate endDate
    ) throws IOException {


        /** 대회에 설정된 코스 순서대로 엑셀 열을 구성한다. */
        List<String> courseNames = categories
                .findAllByEvent_IdOrderByOrderAsc(event.getId())
                .stream()
                .map(category -> category.getName())
                .toList();

        LocalDate openedDate = event.getRegistStartDate().toLocalDate();
        LocalDate eventDate = event.getStartDate().toLocalDate();
        validDate(startDate, endDate, event);

        long[][] cumulativeCounts = new long[6][courseNames.size()];
        Map<LocalDate, long[][]> dailyCounts = new LinkedHashMap<>();

        // 범위 내에 해당하는 모든 신청을 가져온다.
        // 신청 개수 많아지면 분할해서 가져오는 방어로직을 추후 구현하기

        // 여기에 소속된 내역은 소프트딜리트를 제외하므로,
        // 환불 처리 인원을 제외한 내역.
        List<RegistrationStatDto> cumulativeDataList =
                registrationQueryRepository.findStatsByEventIdAndBetweenRegistrationDate(
                        event.getId(),
                        event.getRegistStartDate(),
                        endDate.plusDays(1).atStartOfDay()
                );

        /** 한 번 조회한 결과를 신청일별로 분류한다. */
        Map<LocalDate, List<RegistrationStatDto>> registrationsByDate =
                cumulativeDataList.stream()
                        .collect(Collectors.groupingBy(
                                dto -> dto.registrationDate().toLocalDate()
                        ));

        /** 선택한 기간의 각 날짜를 독립적으로 집계한다. */
        for (LocalDate indexDate = openedDate;
             !indexDate.isAfter(endDate);
             indexDate = indexDate.plusDays(1)) {

            long[][] counts = new long[6][courseNames.size()];

            LocalDateTime dayStart = indexDate.atStartOfDay();

            /** 접수 시작일에는 실제 접수 시작 시각 이전 신청을 제외한다. */
            if (dayStart.isBefore(event.getRegistStartDate())) {
                dayStart = event.getRegistStartDate();
            }

            /** 해당 날짜의 신청만 가져온다. 추가 DB 조회는 하지 않는다. */
            List<RegistrationStatDto> dailyDataList =
                    registrationsByDate.getOrDefault(indexDate, List.of());


            for (RegistrationStatDto dto : dailyDataList) {
                /** 취소 접수·취소 완료·만료 신청은 집계에서 제외한다. */
                if (dto.status() == RegistrationStatus.CANCELED
                        || dto.status() == RegistrationStatus.EXPIRED) {
                    continue;
                }

                int courseIndex = courseNames.indexOf(dto.courseName());

                if (courseIndex < 0) {
                    throw new CustomException(
                            ErrorCode.REPORT_CONFIGURATION_INVALID,
                            " 집계 대상 코스가 대회 코스 목록에 없습니다."
                    );
                }

                boolean adult;
                try {
                    /** 나이 판정은 신청별 한 번만 수행한다. */
                    adult = adultValidator(dto.birth(), eventDate, 19);
                } catch (IllegalArgumentException exception) {
                    throw new CustomException(
                            ErrorCode.INVALID_BIRTH_DATA,
                            " 참여자 중 이상 수치 확인"
                    );
                }

                int applicantRow = adult ? 0 : 1;
                int paidRow = adult ? 3 : 4;

                /** 신청자 한 명을 당일과 전체 누계에 각각 반영한다. */
                counts[applicantRow][courseIndex]++;
                cumulativeCounts[applicantRow][courseIndex]++;

                /** 현재 순납부액이 있는 신청자를 입금자로 집계한다. */
                if (dto.paidAmount() != null && dto.paidAmount().signum() > 0) {
                    counts[paidRow][courseIndex]++;
                    cumulativeCounts[paidRow][courseIndex]++;
                }
            }
            /** 신청이 없는 날짜도 0명 표를 출력한다. */
            /** 선택 기간에 한해 저장하며 신청이 없는 날짜도 0명으로 포함한다. */
            if (!indexDate.isBefore(startDate)) {
                dailyCounts.put(indexDate, counts);
            }
        }


        /** 합계 행은 기존 엑셀 생성 메서드에서 계산한다. */
        return createReportWorkbook(
                courseNames,
                cumulativeCounts,
                dailyCounts,
                event.getRegistStartDate().toLocalDate(),
                startDate,
                endDate
        );
    }


    /** 보고서 작성에 사용할 대회를 조회한다. */
    public Event getReportEvent(String eventId) {
        return eventCommandRepository.findById(eventId)
                .orElseThrow(() -> new CustomException(
                        ErrorCode.EVENT_NOT_FOUND
                ));
    }

    /** 들어온 일자들이 정상적인지 검증한다. */
    private void validDate(LocalDate startDate, LocalDate endDate, Event event) {

        LocalDate openedDate = event.getRegistStartDate().toLocalDate();
        LocalDate eventDate = event.getStartDate().toLocalDate();


        /** 조회 시작일이 접수 시작일보다 빠르면 접수 시작일로 보정한다. */
        if (startDate == null || startDate.isBefore(openedDate)) {
            startDate = openedDate;
        }
        /** 조회 종료일이 대회 시작일보다 늦으면 대회 시작일로 보정한다. */
        if (endDate == null || endDate.isAfter(eventDate)) {
            endDate = eventDate;
        }

        /** 조회 종료일이 오늘 이후이면 거절한다. */
        if (endDate.isAfter(startDate)) {
            throw new CustomException(
                    ErrorCode.REPORT_END_DATE_AFTER_TODAY
            );
        }

        /** 시작일과 종료일을 포함하여 최대 366일까지 허용한다. */
        if (endDate.isAfter(startDate.plusDays(365))) {
            throw new CustomException(
                    ErrorCode.REPORT_DATE_RANGE_INVALID
            );
        }
    }



    /**
     * 대회일 기준으로 지정된 나이에 도달(이상)했는지 확인한다.
     */

    private boolean adultValidator(
            String birthStr,
            LocalDate eventStartDate,
            int limitAge
    ) {
        if (birthStr == null || birthStr.isBlank()) {
            throw new IllegalArgumentException("생년월일이 없습니다.");
        }
        if (eventStartDate == null || limitAge < 1) {
            throw new IllegalArgumentException("대회일 또는 성인 나이 기준이 올바르지 않습니다.");
        }

        String cleanBirth = birthStr.replaceAll("[^0-9]", "");

        if (cleanBirth.length() != 8) {
            throw new IllegalArgumentException("정상적인 생년월일 양식이 아닙니다.");
        }

        LocalDate birthDate;
        try {
            birthDate = LocalDate.parse(
                    cleanBirth,
                    DateTimeFormatter.BASIC_ISO_DATE
            );
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    "유효하지 않은 생년월일입니다.",
                    exception
            );
        }

        if (birthDate.isAfter(eventStartDate)) {
            throw new IllegalArgumentException("생년월일이 대회일 이후입니다.");
        }

        return Period.between(birthDate, eventStartDate).getYears() >= limitAge;
    }


    /**
     * 누계·일자별 시트를 생성한다. 반환된 워크북의 종료는 호출자가 담당한다.
     */
    private SXSSFWorkbook createReportWorkbook(
            List<String> courseNames,
            long[][] cumulativeCounts,
            Map<LocalDate, long[][]> dailyCounts,
            LocalDate eventRegistStartDate,
            LocalDate startDate,
            LocalDate endDate
    ) throws IOException {
        SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        boolean completed = false;

        try {
            workbook.setCompressTempFiles(true);
            Font normalFont = workbook.createFont();
            normalFont.setFontName("맑은 고딕");
            normalFont.setFontHeightInPoints((short) 11);

            Font boldFont = workbook.createFont();
            boldFont.setFontName("맑은 고딕");
            boldFont.setFontHeightInPoints((short) 11);
            boldFont.setBold(true);

            CellStyle baseStyle = workbook.createCellStyle();
            baseStyle.setFont(normalFont);
            baseStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            baseStyle.setBorderTop(BorderStyle.THIN);
            baseStyle.setBorderBottom(BorderStyle.THIN);
            baseStyle.setBorderLeft(BorderStyle.THIN);
            baseStyle.setBorderRight(BorderStyle.THIN);
            baseStyle.setTopBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            baseStyle.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            baseStyle.setLeftBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            baseStyle.setRightBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());

            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.cloneStyleFrom(baseStyle);
            headerStyle.setFont(boldFont);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle labelStyle = workbook.createCellStyle();
            labelStyle.cloneStyleFrom(baseStyle);
            labelStyle.setFont(boldFont);

            CellStyle numberStyle = workbook.createCellStyle();
            numberStyle.cloneStyleFrom(baseStyle);
            numberStyle.setAlignment(HorizontalAlignment.CENTER);
            numberStyle.setDataFormat(
                    workbook.createDataFormat().getFormat("#,##0")
            );

            CellStyle totalStyle = workbook.createCellStyle();
            totalStyle.cloneStyleFrom(numberStyle);
            totalStyle.setFont(boldFont);
            totalStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            totalStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle totalLabelStyle = workbook.createCellStyle();
            totalLabelStyle.cloneStyleFrom(totalStyle);
            totalLabelStyle.setAlignment(HorizontalAlignment.LEFT);

            CellStyle sumStyle = workbook.createCellStyle();
            sumStyle.cloneStyleFrom(numberStyle);
            sumStyle.setFont(boldFont);
            sumStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            sumStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle titleStyle = workbook.createCellStyle();
            titleStyle.setFont(boldFont);
            titleStyle.setVerticalAlignment(VerticalAlignment.CENTER);

            String[] labels = {
                    "신청자(일반)",
                    "신청자(아동)",
                    "신청자(합계)",
                    "입금자(일반)",
                    "입금자(아동)",
                    "입금자(합계)"
            };

            int courseCount = courseNames.size();
            int lastColumn = courseCount + 1;

            /** 첫 번째 시트에는 최종 누계 하나, 두 번째에는 날짜별 표를 작성한다. */
            for (int sheetIndex = 0; sheetIndex < 2; sheetIndex++) {
                boolean cumulative = sheetIndex == 0;
                Sheet sheet = workbook.createSheet(cumulative ? "누계" : "일자별");

                sheet.setDisplayGridlines(false);
                sheet.setColumnWidth(0, 23 * 256);

                for (int column = 1; column <= lastColumn; column++) {
                    sheet.setColumnWidth(column, 18 * 256);
                }

                int rowIndex = 0;

                for (LocalDate date = startDate;
                     !date.isAfter(endDate);
                     date = date.plusDays(1)) {

                    long[][] counts = cumulative
                            ? cumulativeCounts
                            : dailyCounts.getOrDefault(
                            date,
                            new long[6][courseCount]
                    );

                    Row titleRow = sheet.createRow(rowIndex++);
                    titleRow.setHeightInPoints(28);

                    Cell titleCell = titleRow.createCell(0);
                    titleCell.setCellStyle(titleStyle);
                    titleCell.setCellValue(
                            cumulative
                                    ? "아동 유무별 · 누계 · "
                                    + eventRegistStartDate
                                    + " ~ " + endDate
                                    : "아동 유무별 · " + date
                    );

                    sheet.addMergedRegion(new CellRangeAddress(
                            titleRow.getRowNum(),
                            titleRow.getRowNum(),
                            0,
                            lastColumn
                    ));

                    /** 상단 헤더는 구분 / 코스별 / 합계의 두 줄 구조로 작성한다. */
                    int headerStartRow = rowIndex;
                    Row topHeader = sheet.createRow(rowIndex++);
                    Row bottomHeader = sheet.createRow(rowIndex++);

                    topHeader.setHeightInPoints(25);
                    bottomHeader.setHeightInPoints(23);

                    for (int column = 0; column <= lastColumn; column++) {
                        topHeader.createCell(column).setCellStyle(headerStyle);
                        bottomHeader.createCell(column).setCellStyle(headerStyle);
                    }

                    topHeader.getCell(0).setCellValue("구분");
                    topHeader.getCell(lastColumn).setCellValue("합계");
                    bottomHeader.getCell(lastColumn).setCellValue("인원");

                    sheet.addMergedRegion(new CellRangeAddress(
                            headerStartRow,
                            headerStartRow + 1,
                            0,
                            0
                    ));

                    if (courseCount > 0) {
                        topHeader.getCell(1).setCellValue("코스별");

                        if (courseCount > 1) {
                            sheet.addMergedRegion(new CellRangeAddress(
                                    headerStartRow,
                                    headerStartRow,
                                    1,
                                    courseCount
                            ));
                        }

                        for (int course = 0; course < courseCount; course++) {
                            bottomHeader.getCell(course + 1)
                                    .setCellValue(courseNames.get(course));
                        }
                    }

                    /** 코스별 인원과 행 합계를 숫자 셀로 작성한다. */
                    for (int line = 0; line < labels.length; line++) {
                        Row row = sheet.createRow(rowIndex++);
                        row.setHeightInPoints(25);

                        boolean totalLine = line == 2 || line == 5;

                        Cell labelCell = row.createCell(0);
                        labelCell.setCellValue(labels[line]);
                        labelCell.setCellStyle(
                                totalLine ? totalLabelStyle : labelStyle
                        );

                        long sum = 0;

                        for (int course = 0; course < courseCount; course++) {
                            long count;

                            if (line == 2) {
                                count = counts[0][course] + counts[1][course];
                            } else if (line == 5) {
                                count = counts[3][course] + counts[4][course];
                            } else {
                                count = counts[line][course];
                            }

                            Cell cell = row.createCell(course + 1);
                            cell.setCellValue(count);
                            cell.setCellStyle(totalLine ? totalStyle : numberStyle);
                            sum += count;
                        }

                        Cell sumCell = row.createCell(lastColumn);
                        sumCell.setCellValue(sum);
                        sumCell.setCellStyle(totalLine ? totalStyle : sumStyle);
                    }

                    rowIndex += 2;

                    /** 누계 시트에는 날짜별 반복 없이 표 하나만 출력한다. */
                    if (cumulative) {
                        break;
                    }
                }

                sheet.getPrintSetup().setLandscape(true);
                sheet.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
                sheet.getPrintSetup().setFitWidth((short) 1);
                sheet.getPrintSetup().setFitHeight((short) 0);
                sheet.setFitToPage(true);
            }

            completed = true;
            return workbook;

        } finally {
            /** 생성 실패 시에는 컨트롤러에 전달되지 않으므로 여기서 정리한다. */
            if (!completed) {
                try {
                    workbook.close();
                } finally {
                    workbook.dispose();
                }
            }
        }
    }
}
