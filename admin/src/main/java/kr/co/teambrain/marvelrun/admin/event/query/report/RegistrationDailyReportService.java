package kr.co.teambrain.marvelrun.admin.event.query.report;

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
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.admin.event.query.repository.EventQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.EventCategoryQueryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 날짜 범위와 코스 순서를 검증하고 일별 집계로 당일·누계 표를 구성한다. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RegistrationDailyReportService {
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Seoul");
    private final EventQueryRepository events;
    private final EventCategoryQueryRepository categories;
    private final RegistrationDailyReportRepository repository;
    private final Clock clock;
    private final int offsetMinutes;

    /** 그래프와 동일한 승인 시각 저장 오프셋 설정을 사용한다. 기본은 UTC이다. */
    @Autowired
    public RegistrationDailyReportService(EventQueryRepository events, EventCategoryQueryRepository categories,
            RegistrationDailyReportRepository repository,
            @Value("${reporting.payment-graph.approved-at-offset:+00:00}") String approvedAtOffset) {
        this(events, categories, repository, Clock.system(REPORT_ZONE), ZoneOffset.of(approvedAtOffset));
    }

    /** 날짜 경계 검증 시 조회 시각과 승인 저장 기준을 고정할 수 있게 한다. */
    RegistrationDailyReportService(EventQueryRepository events, EventCategoryQueryRepository categories,
            RegistrationDailyReportRepository repository, Clock clock, ZoneOffset approvedAtOffset) {
        this.events = events;
        this.categories = categories;
        this.repository = repository;
        this.clock = clock;
        this.offsetMinutes = (ZoneOffset.ofHours(9).getTotalSeconds() - approvedAtOffset.getTotalSeconds()) / 60;
    }

    /** 기본 접수 시작일~어제, 최대 366일의 표를 만들며 누계는 선택 시작일 이전도 포함한다. */
    public RegistrationDailyReport getDailyPaymenterReport(String eventId, LocalDate requestedStart, LocalDate requestedEnd) {
        LocalDateTime generatedAt = LocalDateTime.now(clock.withZone(REPORT_ZONE));
        Event event = events.findById(eventId).orElseThrow(() -> new CustomException(ErrorCode.EVENT_NOT_FOUND));
        LocalDateTime openedAt = event.getRegistStartDate();
        if (openedAt == null) {
            throw new CustomException(ErrorCode.REPORT_CONFIGURATION_INVALID);
        }
        LocalDate yesterday = generatedAt.toLocalDate().minusDays(1);
        LocalDate start = requestedStart == null ? openedAt.toLocalDate() : requestedStart;
        LocalDate end = requestedEnd == null ? yesterday : requestedEnd;


        //** 오늘 이후의 조회 종료일은 별도 오류로 안내한다. */
        /** 조회 종료일이 오늘 이후이면 거절한다. */
        if (end.isAfter(generatedAt.toLocalDate())) {
            throw new CustomException(
                    ErrorCode.REPORT_END_DATE_AFTER_TODAY
            );
        }

        /** 조회 시작일이 접수 시작일보다 빠르면 접수 시작일로 보정한다. */
        if (start.isBefore(openedAt.toLocalDate())) {
            start = openedAt.toLocalDate();
        }

        /** 보정된 조회 기간은 시작일과 종료일을 포함하여 최대 366일로 제한한다. */
        if (ChronoUnit.DAYS.between(start, end) >= 366) {
            throw new CustomException(
                    ErrorCode.REPORT_DATE_RANGE_INVALID
            );
        }

        // 코스 목록 도출
        List<EventCategory> entities = categories.findAllByEvent_IdOrderByOrderAsc(eventId);
        List<RegistrationDailyReport.Course> courses = entities.stream()
                .map(category -> new RegistrationDailyReport.Course(category.getId(), category.getName())).toList();


        Map<String,Integer> courseIndexes = new HashMap<>();
        for (int index=0; index<courses.size(); index++) {
            courseIndexes.put(courses.get(index).id(),index);
        }


        List<RegistrationDailyReportRepository.Aggregate> rows = repository.aggregate(
                eventId,openedAt,end.plusDays(1).atStartOfDay(),offsetMinutes);
        long[][] cumulative = new long[courses.size()][4];
        Map<LocalDate,long[][]> dailyValues = new HashMap<>();
        for (RegistrationDailyReportRepository.Aggregate row : rows) {
            Integer courseIndex = courseIndexes.get(row.courseId());
            if (courseIndex == null) {
                throw new CustomException(ErrorCode.REPORT_CONFIGURATION_INVALID, " 집계 코스가 대회 목록에 없습니다.");
            }
            if (row.date().isBefore(start)) {
                add(cumulative[courseIndex],row);
            } else {
                long[][] values = dailyValues.computeIfAbsent(row.date(),date -> new long[courses.size()][4]);
                add(values[courseIndex],row);
            }
        }
        List<RegistrationDailyReport.Day> days = new ArrayList<>();
        for (LocalDate date=start; !date.isAfter(end); date=date.plusDays(1)) {
            long[][] daily = dailyValues.getOrDefault(date,new long[courses.size()][4]);
            for (int course=0; course<courses.size(); course++) {
                for (int column=0; column<4; column++) {
                    cumulative[course][column] += daily[course][column];
                }
            }
            days.add(new RegistrationDailyReport.Day(date,freeze(daily),freeze(cumulative)));
        }
        return new RegistrationDailyReport(eventId,event.getNameKr(),openedAt,start,end,generatedAt,
                List.copyOf(courses),List.copyOf(days));
    }

    /** 신청자와 결제자의 기준일은 독립적이며 같은 날의 두 수를 비교 제한하지 않는다. */
    private void add(long[] values, RegistrationDailyReportRepository.Aggregate row) {
        int childIndex = row.child() ? 1 : 0;
        values[childIndex] += row.applicants();
        values[2+childIndex] += row.paid();
    }

    /** 누계 배열을 복사해 이후 날짜 계산이 앞 날짜 표를 변경하지 않도록 한다. */
    private List<RegistrationDailyReport.Counts> freeze(long[][] values) {
        List<RegistrationDailyReport.Counts> result = new ArrayList<>();
        for (long[] value : values) {
            result.add(new RegistrationDailyReport.Counts(value[0],value[1],value[2],value[3]));
        }
        return List.copyOf(result);
    }
}
