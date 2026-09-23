package kr.co.teambrain.marvelrun.admin.event.query.graph;

import java.time.LocalDate;
import java.util.List;

/** 현재 유효 결제자를 최초 결제일별로 제공하며 취소·전액환불 시 과거 수치도 갱신한다. */
public record PaymentDailyGraphResponse(
        String eventId, LocalDate startDate, LocalDate endDate, String timeZone,
        long openingCumulativeCount, long periodTotal, long cumulativeTotal,
        List<Day> days) {
    /** 현재 유효한 결제자 중 해당 일 최초 결제 인원과 접수 시작 이후 누계이다. */
    public record Day(LocalDate date, long dailyCount, long cumulativeCount) { }
}
