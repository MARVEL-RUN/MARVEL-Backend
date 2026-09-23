package kr.co.teambrain.marvelrun.admin.event.query.graph;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.query.repository.EventQueryRepository;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 조회 범위와 누계의 시작점, 시간대 변환 전달을 DB 없이 검증한다. */
class PaymentDailyGraphServiceTest {
    /** 선택 기간 앞의 유입은 누계에만 포함하고 빈 날짜는 0으로 반환한다. */
    @Test
    void preservesOpeningAndFillsEmptyDays() {
        EventQueryRepository events = mock(EventQueryRepository.class);
        PaymentDailyGraphRepository repository = mock(PaymentDailyGraphRepository.class);
        Event event = mock(Event.class);
        LocalDateTime openedAt = LocalDateTime.of(2026, 9, 1, 10, 0);
        when(event.getRegistStartDate()).thenReturn(openedAt);
        when(events.findById("event")).thenReturn(Optional.of(event));
        when(repository.findDailyCounts("event", openedAt, LocalDate.of(2026,9,7).atStartOfDay(), 540))
                .thenReturn(List.of(
                        new PaymentDailyGraphRepository.DailyCount(LocalDate.of(2026,9,2), 300),
                        new PaymentDailyGraphRepository.DailyCount(LocalDate.of(2026,9,5), 1),
                        new PaymentDailyGraphRepository.DailyCount(LocalDate.of(2026,9,6), 3)));
        PaymentDailyGraphService service = new PaymentDailyGraphService(events, repository,
                Clock.fixed(Instant.parse("2026-09-06T03:00:00Z"), ZoneOffset.UTC), ZoneOffset.UTC);
        PaymentDailyGraphResponse result = service.get("event", LocalDate.of(2026,9,4), LocalDate.of(2026,9,6));
        assertThat(result.openingCumulativeCount()).isEqualTo(300);
        assertThat(result.periodTotal()).isEqualTo(4);
        assertThat(result.cumulativeTotal()).isEqualTo(304);
        assertThat(result.days()).containsExactly(
                new PaymentDailyGraphResponse.Day(LocalDate.of(2026,9,4), 0, 300),
                new PaymentDailyGraphResponse.Day(LocalDate.of(2026,9,5), 1, 301),
                new PaymentDailyGraphResponse.Day(LocalDate.of(2026,9,6), 3, 304));
    }

    /** 잘못된 기간은 DB 집계 전에 거절한다. */
    @Test
    void rejectsFutureReversedAndBeforeOpeningRanges() {
        EventQueryRepository events = mock(EventQueryRepository.class);
        PaymentDailyGraphRepository repository = mock(PaymentDailyGraphRepository.class);
        Event event = mock(Event.class);
        when(event.getRegistStartDate()).thenReturn(LocalDateTime.of(2026,9,1,0,0));
        when(events.findById("event")).thenReturn(Optional.of(event));
        PaymentDailyGraphService service = new PaymentDailyGraphService(events, repository,
                Clock.fixed(Instant.parse("2026-09-06T03:00:00Z"), ZoneOffset.UTC), ZoneOffset.ofHours(9));
        assertThatThrownBy(() -> service.get("event", LocalDate.of(2026,8,31), LocalDate.of(2026,9,6)))
                .isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> service.get("event", LocalDate.of(2026,9,6), LocalDate.of(2026,9,5)))
                .isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> service.get("event", LocalDate.of(2026,9,1), LocalDate.of(2026,9,7)))
                .isInstanceOf(CustomException.class);
        verifyNoInteractions(repository);
    }
    /** 실제 설정을 받는 생성자가 UTC는 9시간, KST는 무보정으로 조회에 전달하는지 확인한다. */
    @Test
    void configuredStorageOffsetsReachRepository() {
        for (String offset : List.of("+00:00", "+09:00")) {
            EventQueryRepository events = mock(EventQueryRepository.class);
            PaymentDailyGraphRepository repository = mock(PaymentDailyGraphRepository.class);
            Event event = mock(Event.class);
            LocalDateTime openedAt = LocalDateTime.of(2020,1,1,0,0);
            when(event.getRegistStartDate()).thenReturn(openedAt);
            when(events.findById("event")).thenReturn(Optional.of(event));
            PaymentDailyGraphService service = new PaymentDailyGraphService(events, repository, offset);
            service.get("event", LocalDate.of(2020,1,1), LocalDate.of(2020,1,2));
            verify(repository).findDailyCounts("event", openedAt,
                    LocalDate.of(2020,1,3).atStartOfDay(), offset.equals("+00:00") ? 540 : 0);
        }
    }
}
