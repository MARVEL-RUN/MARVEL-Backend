package kr.co.teambrain.marvelrun.admin.event.query.report;

import java.time.*;
import java.util.List;
import java.util.Optional;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.admin.event.query.repository.EventQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.EventCategoryQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 선택 시작일 이전 누계와 날짜별 불변 결과, 어제까지 제한을 DB 없이 검증한다. */
class RegistrationDailyReportServiceTest {
    private EventQueryRepository events;
    private EventCategoryQueryRepository categories;
    private RegistrationDailyReportRepository repository;
    private RegistrationDailyReportService service;
    private Event event;
    private final LocalDateTime openedAt = LocalDateTime.of(2026,9,1,10,0);

    /** 한국시간 9월 7일 00시의 요청을 재현한다. */
    @BeforeEach
    void setup() {
        events = mock(EventQueryRepository.class);
        categories = mock(EventCategoryQueryRepository.class);
        repository = mock(RegistrationDailyReportRepository.class);
        event = mock(Event.class);
        when(events.findById("event")).thenReturn(Optional.of(event));
        when(event.getRegistStartDate()).thenReturn(openedAt);
        when(event.getNameKr()).thenReturn("마블런");
        EventCategory first = mock(EventCategory.class);
        EventCategory second = mock(EventCategory.class);
        when(first.getId()).thenReturn("a"); when(first.getName()).thenReturn("5km");
        when(second.getId()).thenReturn("b"); when(second.getName()).thenReturn("5km");
        when(categories.findAllByEvent_IdOrderByOrderAsc("event")).thenReturn(List.of(first,second));
        service = new RegistrationDailyReportService(events,categories,repository,
                Clock.fixed(Instant.parse("2026-09-06T15:00:00Z"),ZoneOffset.UTC),ZoneOffset.UTC);
    }

    /** 동일 이름 코스를 합치지 않으며 앞 날짜 누계가 이후 날짜 계산으로 바뀌지 않는다. */
    @Test
    void preservesOpeningCountsAndSeparateDateBases() {
        when(repository.aggregate("event",openedAt,LocalDate.of(2026,9,7).atStartOfDay(),540))
                .thenReturn(List.of(
                        new RegistrationDailyReportRepository.Aggregate(LocalDate.of(2026,9,2),"a",false,300,200),
                        new RegistrationDailyReportRepository.Aggregate(LocalDate.of(2026,9,5),"a",true,2,0),
                        new RegistrationDailyReportRepository.Aggregate(LocalDate.of(2026,9,6),"a",true,0,2)));
        RegistrationDailyReport result = service.get("event",LocalDate.of(2026,9,4),LocalDate.of(2026,9,6));
        assertThat(result.days()).hasSize(3);
        assertThat(result.courses()).extracting(RegistrationDailyReport.Course::id).containsExactly("a","b");
        assertThat(result.days().get(0).daily().get(0)).isEqualTo(new RegistrationDailyReport.Counts(0,0,0,0));
        assertThat(result.days().get(0).cumulative().get(0)).isEqualTo(new RegistrationDailyReport.Counts(300,0,200,0));
        assertThat(result.days().get(1).daily().get(0)).isEqualTo(new RegistrationDailyReport.Counts(0,2,0,0));
        assertThat(result.days().get(2).daily().get(0)).isEqualTo(new RegistrationDailyReport.Counts(0,0,0,2));
        assertThat(result.days().get(2).cumulative().get(0)).isEqualTo(new RegistrationDailyReport.Counts(300,2,200,2));
        assertThat(result.days().get(2).cumulative().get(1)).isEqualTo(new RegistrationDailyReport.Counts(0,0,0,0));
    }

    /** 기본 종료일은 서버 UTC 날짜가 아니라 한국시간 어제이다. */
    @Test
    void defaultsToKstYesterday() {
        RegistrationDailyReport result = service.get("event",null,null);
        assertThat(result.startDate()).isEqualTo(LocalDate.of(2026,9,1));
        assertThat(result.endDate()).isEqualTo(LocalDate.of(2026,9,6));
        verify(repository).aggregate("event",openedAt,LocalDate.of(2026,9,7).atStartOfDay(),540);
    }

    /** 오늘·역전·접수 전 날짜는 SQL 실행 전에 거절한다. */
    @Test
    void rejectsInvalidRangesBeforeQuery() {
        assertThatThrownBy(() -> service.get("event",LocalDate.of(2026,9,1),LocalDate.of(2026,9,7)))
                .isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> service.get("event",LocalDate.of(2026,9,6),LocalDate.of(2026,9,5)))
                .isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> service.get("event",LocalDate.of(2026,8,31),LocalDate.of(2026,9,6)))
                .isInstanceOf(CustomException.class);
        verifyNoInteractions(repository);
    }

    /** 최대 366일을 넘는 출력과 누락된 접수 시작 설정을 거절한다. */
    @Test
    void rejectsOversizedRangeAndMissingOpening() {
        when(event.getRegistStartDate()).thenReturn(LocalDateTime.of(2020,1,1,0,0));
        assertThatThrownBy(() -> service.get("event",LocalDate.of(2020,1,1),LocalDate.of(2021,1,1)))
                .isInstanceOf(CustomException.class);
        when(event.getRegistStartDate()).thenReturn(null);
        assertThatThrownBy(() -> service.get("event",null,null)).isInstanceOf(CustomException.class)
                .satisfies(error -> assertThat(((CustomException)error).getErrorCode())
                        .isEqualTo(ErrorCode.REPORT_CONFIGURATION_INVALID));
        verifyNoInteractions(repository);
    }
}
