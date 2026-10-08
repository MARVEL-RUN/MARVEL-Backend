package kr.co.teambrain.marvelrun.admin.event.query.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.io.IOException;
import kr.co.teambrain.marvelrun.admin.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.admin.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.EventCategoryQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationDailyReportQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.report.RegistrationDailyReportRow;
import kr.co.teambrain.marvelrun.admin.event.query.dto.PaymentDailyCountRow;
import kr.co.teambrain.marvelrun.admin.event.query.dto.PaymentDailyGraphResponse;
import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationStatDto;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentMethod;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/** DB 없이 아동 분류, KST 승인일의 엑셀 누계·일별 배정과 그래프 누계를 검증한다. */
class RegistrationDailyReportServiceTest {

    private final RegistrationDailyReportService report =
            mock(RegistrationDailyReportService.class, CALLS_REAL_METHODS);

    private final RegistrationQueryService statistics =
            mock(RegistrationQueryService.class, CALLS_REAL_METHODS);

    /** 경계일 전후와 과거 만 19세 기준에 잘못 분류되던 청소년을 화면 통계와 비교한다. */
    @ParameterizedTest
    @CsvSource({
            "2013-10-31, 일반",
            "2013-11-01, 아동",
            "2013-11-02, 아동",
            "20131101, 아동",
            "2010-01-01, 일반"
    })
    void matchesStatisticsBoundary(String birth, String expectedGroup) {
        String statisticsGroup = ReflectionTestUtils.invokeMethod(statistics, "getChildGroup", birth);

        for (LocalDate eventDate : new LocalDate[]{LocalDate.of(2026, 11, 1), LocalDate.of(2035, 11, 1)}) {
            Boolean general = ReflectionTestUtils.invokeMethod(report, "adultValidator", birth, eventDate);

            assertThat(statisticsGroup).isEqualTo(expectedGroup);
            assertThat(general).isEqualTo("일반".equals(expectedGroup));
        }
    }

    /** 날짜 기준 변경 후에도 리포트의 누락·잘못된 날짜·대회일 이후 출생 검증을 유지한다. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "invalid", "2013-13-01", "2027-01-01"})
    void preservesInvalidBirthValidation(String birth) {
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                report, "adultValidator", birth, LocalDate.of(2026, 11, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 승인 전 종료일은 누계에서 제외하고 오늘 포함 시 정상 자료의 statistics 인원과 일치한다. */
    @ParameterizedTest
    @CsvSource({"2026-10-06, 1", "2026-10-08, 2"})
    void excelUsesApprovalDateForDailyAndCumulativeCounts(String end, int expectedPaid) throws IOException {
        // 동일 신청일에 접수하고 승인일만 다른 자료와 승인일 누락 자료를 준비한다.
        LocalDate opened = LocalDate.of(2026, 10, 5);
        LocalDate endDate = LocalDate.parse(end);
        Event event = reportEvent(opened);
        ServerTimeProvider time = mock(ServerTimeProvider.class);
        when(time.currentDateTime()).thenReturn(LocalDateTime.of(2026, 10, 8, 12, 0));
        EventCategoryQueryRepository categories = mock(EventCategoryQueryRepository.class);
        EventCategory category = mock(EventCategory.class);
        when(category.getName()).thenReturn("A");
        when(categories.findAllByEvent_IdOrderByOrderDesc("event")).thenReturn(List.of(category));
        RegistrationDailyReportQueryRepository repository = mock(RegistrationDailyReportQueryRepository.class);
        BigDecimal amount = new BigDecimal("40000");
        List<RegistrationDailyReportRow> rows = List.of(
                new RegistrationDailyReportRow(RegistrationStatus.CONFIRMED, "1990-01-01", "A",
                        amount, opened.atTime(10, 0), opened.plusDays(1).atStartOfDay()),
                new RegistrationDailyReportRow(RegistrationStatus.CONFIRMED, "2014-01-01", "A",
                        amount, opened.atTime(11, 0), opened.plusDays(3).atStartOfDay()),
                new RegistrationDailyReportRow(RegistrationStatus.CONFIRMED, "1990-01-01", "A",
                        amount, opened.atTime(12, 0), null));
        when(repository.findReportRows("event", opened.atStartOfDay(), endDate.plusDays(1).atStartOfDay()))
                .thenReturn(rows);
        RegistrationDailyReportService service = new RegistrationDailyReportService(time, categories,
                mock(EventCommandRepository.class), mock(RegistrationQueryRepository.class), repository);

        // 실제 엑셀에서 신청일과 승인일의 배정 및 승인일 누락 제외를 확인한다.
        SXSSFWorkbook workbook = service.getDailyPaymenterExcelReport(event, opened, endDate);
        try (workbook) {
            assertThat(totals(workbook.getSheet("누계"), "입금자(합계)"))
                    .containsExactly((double) expectedPaid);
            assertThat(totals(workbook.getSheet("누계"), "신청자(합계)"))
                    .containsExactly(3.0);
            assertThat(totals(workbook.getSheet("일자별"), "입금자(합계)"))
                    .containsExactlyElementsOf(expectedPaid == 1
                            ? List.of(0.0, 1.0) : List.of(0.0, 1.0, 0.0, 1.0));
        } finally {
            workbook.dispose();
        }

        // 정상 승인 자료 두 건은 오늘 포함 누계와 statistics의 실제 집계가 같다.
        if (expectedPaid == 2) {
            RegistrationQueryRepository statsRepository = mock(RegistrationQueryRepository.class);
            when(statsRepository.findStatsByEventId("event")).thenReturn(List.of(
                    new RegistrationStatDto(RegistrationStatus.CONFIRMED, null, GenderClass.M,
                            "1990-01-01", "A", amount, amount, PaymentMethod.CARD),
                    new RegistrationStatDto(RegistrationStatus.CONFIRMED, null, GenderClass.M,
                            "2014-01-01", "A", amount, amount, PaymentMethod.CARD)));
            ReflectionTestUtils.setField(statistics, "registrationQueryRepository", statsRepository);
            assertThat(statistics.getEventStatistics("event").childStats().stream()
                    .filter(row -> row.classification().equals("입금자(합계)"))
                    .findFirst().orElseThrow().totalCount()).isEqualTo(expectedPaid);
        }
    }

    /** 그래프는 KST 조회 결과를 재보정하지 않고 이전 누계·빈 날짜·당일을 함께 반영한다. */
    @Test
    void graphPreservesOpeningCumulativeAndIncludesExplicitToday() {
        // 조회 시작 전 승인과 조회 기간 내 승인을 구분한다.
        LocalDate opened = LocalDate.of(2026, 10, 5);
        LocalDate today = LocalDate.of(2026, 10, 8);
        Event event = reportEvent(opened);
        ServerTimeProvider time = mock(ServerTimeProvider.class);
        when(time.currentDateTime()).thenReturn(today.atTime(12, 0));
        when(time.timeZone()).thenReturn("Asia/Seoul");
        RegistrationDailyReportQueryRepository repository = mock(RegistrationDailyReportQueryRepository.class);
        when(repository.findPaymentDailyCounts("event", opened.atStartOfDay(), today.plusDays(1).atStartOfDay()))
                .thenReturn(List.of(new PaymentDailyCountRow(opened, 2),
                        new PaymentDailyCountRow(today, 1)));
        when(repository.findPaymentDailyCounts("event", opened.atStartOfDay(), today.atStartOfDay()))
                .thenReturn(List.of(new PaymentDailyCountRow(opened, 2)));
        RegistrationDailyReportService service = new RegistrationDailyReportService(time,
                mock(EventCategoryQueryRepository.class), mock(EventCommandRepository.class),
                mock(RegistrationQueryRepository.class), repository);

        // 오늘 명시 시 포함하고 미지정 시 전날까지 조회하는 기존 계약을 보존한다.
        PaymentDailyGraphResponse graph = service.getPaymentDailyGraph(event, opened.plusDays(1), today);
        assertThat(graph.timeZone()).isEqualTo("Asia/Seoul");
        assertThat(graph.openingCumulativeCount()).isEqualTo(2);
        assertThat(graph.periodTotal()).isEqualTo(1);
        assertThat(graph.cumulativeTotal()).isEqualTo(3);
        assertThat(graph.days()).containsExactly(
                new PaymentDailyGraphResponse.Day(opened.plusDays(1), 0, 2),
                new PaymentDailyGraphResponse.Day(opened.plusDays(2), 0, 2),
                new PaymentDailyGraphResponse.Day(today, 1, 3));
        PaymentDailyGraphResponse defaultGraph = service.getPaymentDailyGraph(event, opened.plusDays(1), null);
        assertThat(defaultGraph.endDate()).isEqualTo(today.minusDays(1));
        assertThat(defaultGraph.cumulativeTotal()).isEqualTo(2);
        verify(repository).findPaymentDailyCounts("event", opened.atStartOfDay(), today.atStartOfDay());
    }

    /** 테스트 대회에 접수 시작일과 대회일만 제공한다. */
    private Event reportEvent(LocalDate opened) {
        // 날짜 범위 검증에 사용하는 최소 대회 값을 설정한다.
        Event event = mock(Event.class);
        when(event.getId()).thenReturn("event");
        when(event.getRegistStartDate()).thenReturn(opened.atStartOfDay());
        when(event.getStartDate()).thenReturn(LocalDateTime.of(2026, 11, 1, 9, 0));
        return event;
    }

    /** 엑셀 내 동일 이름의 합계 행을 순서대로 읽어 고정 행 번호 의존을 피한다. */
    private List<Double> totals(Sheet sheet, String label) {
        // 단일 종목 표의 마지막 열에 출력된 합계를 수집한다.
        List<Double> values = new java.util.ArrayList<>();
        for (Row row : sheet) {
            if (row.getCell(0) != null && label.equals(row.getCell(0).getStringCellValue())) {
                values.add(row.getCell(2).getNumericCellValue());
            }
        }
        return values;
    }
}
