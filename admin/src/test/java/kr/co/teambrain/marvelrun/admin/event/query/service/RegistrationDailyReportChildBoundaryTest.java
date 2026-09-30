package kr.co.teambrain.marvelrun.admin.event.query.service;

import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

/** DB 조회 없이 리포트와 화면 통계의 실제 분류 메서드가 동일한 출생일 경계를 쓰는지 검증한다. */
class RegistrationDailyReportChildBoundaryTest {

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
}
