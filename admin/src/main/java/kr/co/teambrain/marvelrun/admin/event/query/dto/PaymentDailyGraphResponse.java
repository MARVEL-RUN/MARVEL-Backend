package kr.co.teambrain.marvelrun.admin.event.query.dto;

import java.time.LocalDate;
import java.util.List;

/** 현재 유효 결제자를 최초 결제일별로 제공하며 취소·전액환불 시 과거 수치도 갱신한다. */
import java.time.LocalDate;
import java.util.List;

/**
 * 현재 유효 결제자를 최초 결제일 기준으로 일별 집계한다.
 *
 * 취소 또는 전액 환불되어 현재 유효 입금자가 아니게 된 신청자는
 * 과거 최초 결제일의 집계에서도 제외된다.
 */
public record PaymentDailyGraphResponse(
        String eventId,
        LocalDate startDate,
        LocalDate endDate,
        String timeZone,
        long openingCumulativeCount,
        long periodTotal,
        long cumulativeTotal,
        List<Day> days
) {

    /**
     * 현재 유효 입금자 중 해당 날짜에 최초 결제한 인원과
     * 접수 시작 이후 해당 날짜까지의 누계이다.
     */
    public record Day(
            LocalDate date,
            long dailyCount,
            long cumulativeCount
    ) {
    }
}