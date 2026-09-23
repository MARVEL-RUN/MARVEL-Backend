package kr.co.teambrain.marvelrun.admin.event.query.report;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 현재 상태로 재집계한 날짜별 보고 자료이며 마감 당시 상태의 스냅샷이 아니다. */
public record RegistrationDailyReport(String eventId, String eventName, LocalDateTime openedAt,
        LocalDate startDate, LocalDate endDate, LocalDateTime generatedAt,
        List<Course> courses, List<Day> days) {
    /** 동명 코스를 ID로 구분하고 인원이 없는 코스도 출력한다. */
    public record Course(String id, String name) { }
    /** 코스 하나의 신청자 및 결제자를 일반·아동으로 구분한다. */
    public record Counts(long applicantGeneral, long applicantChild, long paidGeneral, long paidChild) { }
    /** 두 표의 코스 순서는 courses와 동일하며 변경 가능한 배열을 외부에 노출하지 않는다. */
    public record Day(LocalDate date, List<Counts> daily, List<Counts> cumulative) { }
}
