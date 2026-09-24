package kr.co.teambrain.marvelrun.admin.event.query.report;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 일별 신청·입금 집계에 필요한 최소 조회 결과다.
 *
 * registrationDate는 신청자 귀속일,
 * firstPaidAt은 최초 완료 결제 귀속일로 사용한다.
 */
public record RegistrationDailyReportRow(
        RegistrationStatus status,
        String birth,
        String courseName,
        BigDecimal paidAmount,
        LocalDateTime registrationDate,
        LocalDateTime firstPaidAt
) {
}