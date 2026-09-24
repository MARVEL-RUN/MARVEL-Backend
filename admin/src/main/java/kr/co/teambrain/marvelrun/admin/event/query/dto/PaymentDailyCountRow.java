package kr.co.teambrain.marvelrun.admin.event.query.dto;

import java.time.LocalDate;

/**
 * 최초 완료 결제일별 현재 유효 결제자 수다.
 */
public record PaymentDailyCountRow(
        LocalDate date,
        long dailyCount
) {
}