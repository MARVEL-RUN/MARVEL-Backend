package kr.co.teambrain.marvelrun.admin.event.query.service;

import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.admin.event.query.dto.PaymentDailyGraphResponse;
import kr.co.teambrain.marvelrun.admin.event.query.repository.EventCategoryQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationDailyReportQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationQueryRepository;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import kr.co.teambrain.marvelrun.admin.event.query.dto.PaymentDailyCountRow;


import java.io.IOException;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import kr.co.teambrain.marvelrun.admin.event.query.report.RegistrationDailyReportRow;


/**
 * 날짜 범위와 코스 순서를 검증하고 일별 집계로 당일·누계 표를 구성한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RegistrationDailyReportService {
    private final ServerTimeProvider serverTimeProvider;
    private final EventCategoryQueryRepository categories;

    private final EventCommandRepository eventCommandRepository;
    private final RegistrationQueryRepository registrationQueryRepository;

    /**
     * 대량 보고서의 금융 귀속을 set-based SQL로 조회한다.
     */
    private final RegistrationDailyReportQueryRepository
            registrationDailyReportQueryRepository;

    /**
     * 현재 유효 입금자를 최초 완료 결제일 기준으로 일별 집계한다.
     *
     * 금융 귀속과 날짜별 인원 계산은 DB에서 집합 연산으로 수행하고,
     * Java에서는 빈 날짜 보완 및 누계만 계산한다.
     */
    public PaymentDailyGraphResponse getPaymentDailyGraph(
            Event event,
            LocalDate startDate,
            LocalDate endDate
    ) {

        ReportDateRange reportDateRange =
                resolveReportDateRange(
                        startDate,
                        endDate,
                        event
                );

        LocalDate resolvedStartDate =
                reportDateRange.startDate();

        LocalDate resolvedEndDate =
                reportDateRange.endDate();

        /**
         * 접수 시작일부터 조회 종료일까지의 최초 결제자 수를
         * 날짜별 GROUP BY 결과로 가져온다.
         */
        List<PaymentDailyCountRow> paymentCounts =
                registrationDailyReportQueryRepository
                        .findPaymentDailyCounts(
                                event.getId(),
                                event.getRegistStartDate(),
                                resolvedEndDate
                                        .plusDays(1)
                                        .atStartOfDay()
                        );

        /**
         * DB 집계 결과를 날짜 기준 Map으로 변환한다.
         */
        Map<LocalDate, Long> paymentCountByDate =
                new LinkedHashMap<>();

        for (PaymentDailyCountRow row : paymentCounts) {
            paymentCountByDate.put(
                    row.date(),
                    row.dailyCount()
            );
        }

        /**
         * 선택 시작일 이전까지의 현재 유효 결제자 누계다.
         */
        long openingCumulativeCount = 0L;

        for (PaymentDailyCountRow row : paymentCounts) {

            if (row.date().isBefore(resolvedStartDate)) {
                openingCumulativeCount +=
                        row.dailyCount();
            }
        }

        long cumulativeCount =
                openingCumulativeCount;

        long periodTotal = 0L;

        List<PaymentDailyGraphResponse.Day> days =
                new java.util.ArrayList<>();

        /**
         * 신청 또는 결제가 없는 날짜도 그래프에서 누락되지 않도록
         * 조회 기간 전체를 순회한다.
         */
        for (LocalDate date = resolvedStartDate;
             !date.isAfter(resolvedEndDate);
             date = date.plusDays(1)) {

            long dailyCount =
                    paymentCountByDate.getOrDefault(
                            date,
                            0L
                    );

            periodTotal += dailyCount;
            cumulativeCount += dailyCount;

            days.add(
                    new PaymentDailyGraphResponse.Day(
                            date,
                            dailyCount,
                            cumulativeCount
                    )
            );
        }

        return new PaymentDailyGraphResponse(
                event.getId(),
                resolvedStartDate,
                resolvedEndDate,
                serverTimeProvider.timeZone(),
                openingCumulativeCount,
                periodTotal,
                cumulativeCount,
                days
        );
    }
    
    
    
//    /** 개선필요 구코드
//     * 현재 유효 입금자를 최초 완료 결제일 기준으로 일별 집계한다.
//     *
//     * 입금자 판정은 엑셀 보고서와 동일하게
//     * findDailyReportRows()의 결제 귀속 결과를 사용한다.
//     *
//     * 부분환불·추가결제 필요·추가결제 완료 상태에서도
//     * 현재 paidAmount가 양수이면 동일한 최초 결제자 1명으로 유지한다.
//     *
//     * 전액환불 등으로 paidAmount가 0이 되거나 신청이 취소된 경우에는
//     * 최초 결제일의 과거 집계에서도 제외된다.
//     */
//    public PaymentDailyGraphResponse getPaymentDailyGraph(
//            Event event,
//            LocalDate startDate,
//            LocalDate endDate
//    ) {
//
//        ReportDateRange reportDateRange =
//                resolveReportDateRange(startDate, endDate, event);
//
//        LocalDate resolvedStartDate =
//                reportDateRange.startDate();
//
//        LocalDate resolvedEndDate =
//                reportDateRange.endDate();
//
//        /**
//         * startDate 이전 누계도 계산해야 하므로
//         * 접수 시작 시각부터 조회 종료일까지 전체 데이터를 조회한다.
//         */
//        List<RegistrationDailyReportRow> rows =
//                registrationQueryRepository.findDailyReportRows(
//                        event.getId(),
//                        event.getRegistStartDate(),
//                        resolvedEndDate.plusDays(1).atStartOfDay()
//                );
//
//        /**
//         * 선택 기간의 모든 날짜를 먼저 생성한다.
//         * 해당 날짜 결제자가 없어도 그래프에 0명으로 전달한다.
//         */
//        Map<LocalDate, Long> dailyCounts =
//                new LinkedHashMap<>();
//
//        for (LocalDate date = resolvedStartDate;
//             !date.isAfter(resolvedEndDate);
//             date = date.plusDays(1)) {
//
//            dailyCounts.put(date, 0L);
//        }
//
//        long openingCumulativeCount = 0L;
//
//        for (RegistrationDailyReportRow row : rows) {
//
//            /**
//             * 취소 완료 또는 만료된 신청은
//             * 현재 유효 입금자로 보지 않는다.
//             */
//            if (row.status() == RegistrationStatus.CANCELED
//                    || row.status() == RegistrationStatus.EXPIRED) {
//                continue;
//            }
//
//            /**
//             * 입금자 판정:
//             *
//             * 1. 현재 순납부액이 1원 이상 존재하고
//             * 2. 기존 findStatsByEventId와 동일한 결제 귀속 규칙으로
//             *    COMPLETED Payment가 확인되어야 한다.
//             *
//             * firstPaidAt은 Repository에서 기존 결제 귀속 규칙을 이용하여
//             * 최초 완료 결제 시각으로 조회한다.
//             */
//            boolean payer =
//                    isCurrentPayer(row);
//
//            if (!payer) {
//                continue;
//            }
//
//            LocalDate firstPaidDate =
//                    row.firstPaidAt().toLocalDate();
//
//            /**
//             * 조회 종료일 이후 최초 결제자는
//             * 이번 그래프 범위에 포함하지 않는다.
//             */
//            if (firstPaidDate.isAfter(resolvedEndDate)) {
//                continue;
//            }
//
//            /**
//             * 선택 시작일 이전 최초 결제자는
//             * opening 누계에 포함한다.
//             */
//            if (firstPaidDate.isBefore(resolvedStartDate)) {
//                openingCumulativeCount++;
//                continue;
//            }
//
//            /**
//             * 선택 기간 내 최초 결제자는 해당 날짜에 정확히 1번 집계한다.
//             *
//             * 이후 추가결제가 발생하더라도 firstPaidAt은 변하지 않으므로
//             * 그래프 인원이 다시 증가하지 않는다.
//             */
//            dailyCounts.computeIfPresent(
//                    firstPaidDate,
//                    (date, count) -> count + 1L
//            );
//        }
//
//        long cumulativeCount =
//                openingCumulativeCount;
//
//        long periodTotal = 0L;
//
//        List<PaymentDailyGraphResponse.Day> days =
//                new java.util.ArrayList<>();
//
//        for (Map.Entry<LocalDate, Long> entry : dailyCounts.entrySet()) {
//
//            long dailyCount =
//                    entry.getValue();
//
//            periodTotal += dailyCount;
//            cumulativeCount += dailyCount;
//
//            days.add(
//                    new PaymentDailyGraphResponse.Day(
//                            entry.getKey(),
//                            dailyCount,
//                            cumulativeCount
//                    )
//            );
//        }
//
//        return new PaymentDailyGraphResponse(
//                event.getId(),
//                resolvedStartDate,
//                resolvedEndDate,
//                serverTimeProvider.timeZone(),
//                openingCumulativeCount,
//                periodTotal,
//                cumulativeCount,
//                days
//        );
//    }


    /** 통계와 동일한 상태 제외 기준으로 신청자를 집계하고, 최초 완료 결제일별 입금 현황을 엑셀로 생성한다. */
    public SXSSFWorkbook getDailyPaymenterExcelReport(
            Event event,
            LocalDate startDate,
            LocalDate endDate
    ) throws IOException {

        /**
         * null 및 대회/현재 날짜 범위를 포함하여
         * 실제 보고서에서 사용할 기간을 확정한다.
         */
        ReportDateRange reportDateRange =
                resolveReportDateRange(startDate, endDate, event);

        LocalDate resolvedStartDate = reportDateRange.startDate();
        LocalDate resolvedEndDate = reportDateRange.endDate();

        /** 대회에 설정된 코스 순서대로 엑셀 열을 구성한다. */
        List<String> courseNames = categories
                .findAllByEvent_IdOrderByOrderDesc(event.getId())
                .stream()
                .map(category -> category.getName())
                .toList();

        LocalDate openedDate = event.getRegistStartDate().toLocalDate();
        LocalDate eventDate = event.getStartDate().toLocalDate();

        long[][] cumulativeCounts =
                new long[6][courseNames.size()];

        Map<LocalDate, long[][]> dailyCounts =
                new LinkedHashMap<>();

        /**
         * 신청이 없는 날짜도 엑셀에 0명으로 출력하기 위해
         * 선택 기간의 날짜를 먼저 생성한다.
         */
        for (LocalDate date = resolvedStartDate;
             !date.isAfter(resolvedEndDate);
             date = date.plusDays(1)) {

            dailyCounts.put(
                    date,
                    new long[6][courseNames.size()]
            );
        }

        /** 기존방식
         * 누계 계산도 필요하므로 접수 시작일부터 조회 종료일까지의
         * 모든 신청자를 한 번 조회한다.
         */
//        List<RegistrationDailyReportRow> reportRows =
//                registrationQueryRepository.findDailyReportRows(
//                        event.getId(),
//                        event.getRegistStartDate(),
//                        resolvedEndDate.plusDays(1).atStartOfDay()
//                );
        /** 20260924 기능개선
         * Payment / PaymentAllocation에서 신청별 최초 결제를 선집계한
         * 보고서 전용 조회를 사용한다.
         */
        List<RegistrationDailyReportRow> reportRows =
                registrationDailyReportQueryRepository.findReportRows(
                        event.getId(),
                        event.getRegistStartDate(),
                        resolvedEndDate.plusDays(1).atStartOfDay()
                );

        for (RegistrationDailyReportRow row : reportRows) {

            /**
             * 통계와 동일하게 취소 완료·만료 신청은 제외하고, 취소 진행 중인 신청은 유지한다.
             */
            if (row.status() == RegistrationStatus.CANCELED
                    || row.status() == RegistrationStatus.EXPIRED) {
                continue;
            }

            int courseIndex =
                    courseNames.indexOf(row.courseName());

            if (courseIndex < 0) {
                throw new CustomException(
                        ErrorCode.REPORT_CONFIGURATION_INVALID,
                        "집계 대상 코스가 대회 코스 목록에 없습니다."
                );
            }

            boolean adult;

            try {
                /**
                 * 성인·아동 구분은 대회일 기준으로 계산한다.
                 */
                adult = adultValidator(
                        row.birth(),
                        eventDate,
                        19
                );

            } catch (IllegalArgumentException exception) {
                throw new CustomException(
                        ErrorCode.INVALID_BIRTH_DATA,
                        "참여자 중 이상 수치 확인"
                );
            }

            int applicantRow = adult ? 0 : 1;
            int paidRow = adult ? 3 : 4;

            /*
             * ---------------------------------------------------------
             * 신청자
             * ---------------------------------------------------------
             *
             * 신청자는 registrationDate 기준으로 귀속한다.
             */
            LocalDate registrationDate =
                    row.registrationDate().toLocalDate();

            if (!registrationDate.isAfter(resolvedEndDate)) {

                /**
                 * 누계에는 접수 시작일부터 조회 종료일까지의 신청자를 포함한다.
                 */
                cumulativeCounts[applicantRow][courseIndex]++;

                /**
                 * 일자별 시트에는 선택한 조회 기간에 포함되는 신청만 기록한다.
                 */
                if (!registrationDate.isBefore(resolvedStartDate)) {

                    dailyCounts
                            .get(registrationDate)
                            [applicantRow][courseIndex]++;
                }
            }

            /**
             * 현재 순납부액이 존재하면서,
             * 기존 통계의 결제 귀속 규칙으로 COMPLETED Payment가 확인되는 신청자만
             * 현재 입금자로 판단한다.
             */
            boolean payer =
                    isCurrentPayer(row);

            if (!payer) {
                continue;
            }

            /**
             * 추가결제가 있어도 입금자 귀속일은
             * 최초 COMPLETED 결제일을 유지한다.
             */
            LocalDate firstPaidDate =
                    row.firstPaidAt().toLocalDate();

            if (!firstPaidDate.isAfter(resolvedEndDate)) {

                /**
                 * 현재 기준 입금자이면서
                 * 조회 종료일까지 최초 결제를 완료한 사람을 누계에 포함한다.
                 */
                cumulativeCounts[paidRow][courseIndex]++;

                /**
                 * 일자별 입금자는 신청일이 아니라 최초 결제일에 귀속한다.
                 */
                if (!firstPaidDate.isBefore(resolvedStartDate)) {

                    dailyCounts
                            .get(firstPaidDate)
                            [paidRow][courseIndex]++;
                }
            }
        }

        return createReportWorkbook(
                courseNames,
                cumulativeCounts,
                dailyCounts,
                openedDate,
                resolvedStartDate,
                resolvedEndDate
        );
    }


    /** 보고서 작성에 사용할 대회를 조회한다. */
    public Event getReportEvent(String eventId) {
        return eventCommandRepository.findById(eventId)
                .orElseThrow(() -> new CustomException(
                        ErrorCode.EVENT_NOT_FOUND
                ));
    }

    /**
     * 검증과 보정이 완료된 보고서 조회 기간이다.
     */
    private record ReportDateRange(
            LocalDate startDate,
            LocalDate endDate
    ) {
    }
    /** 들어온 일자들이 정상적인지 검증한다. */
    /**
     * 보고서 조회 시작·종료일을 검증하고 실제 사용할 기간을 반환한다.
     *
     * 시작일 생략 시 접수 시작일,
     * 종료일 생략 시 어제까지 조회한다.
     *
     * 대회가 이미 종료된 경우 종료일은 대회일을 넘지 않는다.
     */
    private ReportDateRange resolveReportDateRange(
            LocalDate startDate,
            LocalDate endDate,
            Event event
    ) {

        LocalDate openedDate =
                event.getRegistStartDate().toLocalDate();

        LocalDate eventDate =
                event.getStartDate().toLocalDate();

        LocalDate today =
                serverTimeProvider.currentDateTime().toLocalDate();

        LocalDate yesterday =
                today.minusDays(1);

        /**
         * 시작일 생략 또는 접수 시작일 이전 요청은
         * 실제 접수 시작일로 보정한다.
         */
        LocalDate resolvedStartDate =
                startDate == null || startDate.isBefore(openedDate)
                        ? openedDate
                        : startDate;

        LocalDate resolvedEndDate;

        if (endDate == null) {

            /**
             * 기본 종료일은 어제다.
             *
             * 이미 대회가 종료된 경우에는
             * 대회일을 넘어서 집계하지 않는다.
             */
            resolvedEndDate =
                    eventDate.isBefore(yesterday)
                            ? eventDate
                            : yesterday;

        } else {

            /**
             * 명시적으로 오늘 이후 날짜를 요청하는 것은 허용하지 않는다.
             */
            if (endDate.isAfter(today)) {
                throw new CustomException(
                        ErrorCode.REPORT_END_DATE_AFTER_TODAY
                );
            }

            /**
             * 대회일 이후 날짜는 대회일로 보정한다.
             */
            resolvedEndDate =
                    endDate.isAfter(eventDate)
                            ? eventDate
                            : endDate;
        }

        /**
         * 종료일이 시작일보다 앞설 수 없다.
         */
        if (resolvedEndDate.isBefore(resolvedStartDate)) {
            throw new CustomException(
                    ErrorCode.REPORT_DATE_RANGE_INVALID
            );
        }

        /**
         * 시작일과 종료일을 포함해 최대 366일까지 허용한다.
         */
        if (resolvedEndDate.isAfter(
                resolvedStartDate.plusDays(365)
        )) {
            throw new CustomException(
                    ErrorCode.REPORT_DATE_RANGE_INVALID
            );
        }

        return new ReportDateRange(
                resolvedStartDate,
                resolvedEndDate
        );
    }

    /**
     * 현재 보고서 기준 유효 입금자인지 판단한다.
     *
     * 현재 순납부액이 존재하고 기존 결제 귀속 규칙으로
     * 완료된 최초 결제가 확인되어야 한다.
     */
    private boolean isCurrentPayer(
            RegistrationDailyReportRow row
    ) {

        if (row.status() == RegistrationStatus.CANCELED
                || row.status() == RegistrationStatus.EXPIRED) {
            return false;
        }

        return row.paidAmount() != null
                && row.paidAmount().signum() > 0
                && row.firstPaidAt() != null;
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
