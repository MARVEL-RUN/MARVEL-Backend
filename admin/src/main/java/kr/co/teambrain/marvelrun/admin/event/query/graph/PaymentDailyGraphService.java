package kr.co.teambrain.marvelrun.admin.event.query.graph;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.query.repository.EventQueryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 날짜 검증과 누적 유입 계산을 담당하며 KST 기준 빈 날짜도 반환한다. */
@Service
@Transactional(readOnly = true)
public class PaymentDailyGraphService {
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Seoul");
    private final EventQueryRepository events;
    private final PaymentDailyGraphRepository repository;
    private final Clock clock;
    private final int offsetMinutes;

    /** approved_at은 기본 UTC로 해석하여 KST로 변환한다. 명시 설정으로 KST 저장도 지원한다. */
    @Autowired
    public PaymentDailyGraphService(EventQueryRepository events, PaymentDailyGraphRepository repository,
            @Value("${reporting.payment-graph.approved-at-offset:+00:00}") String approvedAtOffset) {
        this(events, repository, Clock.system(REPORT_ZONE), ZoneOffset.of(approvedAtOffset));
    }

    /** 동일 요청 시각 및 날짜 경계를 고정해 검증하기 위한 생성자이다. */
    PaymentDailyGraphService(EventQueryRepository events, PaymentDailyGraphRepository repository,
                            Clock clock, ZoneOffset storedOffset) {
        this.events = events;
        this.repository = repository;
        this.clock = clock;
        this.offsetMinutes = (ZoneOffset.ofHours(9).getTotalSeconds() - storedOffset.getTotalSeconds()) / 60;
    }

    /** 기본 범위는 접수 시작일~금일이며 누계의 시작점은 대회 접수 시작. */
    public PaymentDailyGraphResponse getPaymentDailyGraph(String eventId, LocalDate requestedStart, LocalDate requestedEnd) {
        Event event = events.findById(eventId)
                .orElseThrow(() -> new CustomException(ErrorCode.EVENT_NOT_FOUND));
        LocalDateTime openedAt = event.getRegistStartDate();
        if (openedAt == null) {
            throw new CustomException(ErrorCode.REPORT_CONFIGURATION_INVALID);
        }
        LocalDate today = LocalDate.now(clock.withZone(REPORT_ZONE));
        LocalDate start = requestedStart == null ? openedAt.toLocalDate() : requestedStart;
        LocalDate end = requestedEnd == null ? today : requestedEnd;



        /** 조회 시작일이 접수 시작일보다 빠르면 접수 시작일로 보정한다. */
        if (start.isBefore(openedAt.toLocalDate())) {
            start = openedAt.toLocalDate();
        }

        /** 오늘 이후의 조회 종료일은 별도 오류로 안내한다. */
        if (end.isAfter(today)) {
            throw new CustomException(
                    ErrorCode.REPORT_END_DATE_AFTER_TODAY
            );
        }

        /** 시작일과 종료일을 포함하여 최대 366일까지만 조회한다. */
        if (ChronoUnit.DAYS.between(start, end) >= 366) {
            throw new CustomException(
                    ErrorCode.REPORT_DATE_RANGE_INVALID
            );
        }

        // plus days 1을 해야 23:59 까지 해당 일지 집계가능
        List<PaymentDailyGraphRepository.DailyCount> rows = repository.findDailyCounts(
                eventId, openedAt, end.plusDays(1).atStartOfDay(), offsetMinutes);

        Map<LocalDate, Long> counts = new HashMap<>();

        long opening = 0;

        for (PaymentDailyGraphRepository.DailyCount row : rows) {
            if (row.date().isBefore(start)) {
                opening += row.count();
            } else {
                counts.merge(row.date(), row.count(), Long::sum);
            }
        }

        long cumulative = opening;

        List<PaymentDailyGraphResponse.Day> days = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            long count = counts.getOrDefault(date, 0L);
            cumulative += count;
            days.add(new PaymentDailyGraphResponse.Day(date, count, cumulative));
        }
        return new PaymentDailyGraphResponse(eventId, start, end, REPORT_ZONE.getId(),
                opening, cumulative - opening, cumulative, List.copyOf(days));
    }
}
