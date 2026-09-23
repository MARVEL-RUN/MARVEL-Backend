package kr.co.teambrain.marvelrun.admin.event.query.graph;

import kr.co.teambrain.marvelrun.admin.event.query.support.PaymentReportSql;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 금융 귀속 원장에서 신청별 최초 참가비 승인 시각을 DB 집계한다. */
@Repository
@RequiredArgsConstructor
public class PaymentDailyGraphRepository {
    private final NamedParameterJdbcTemplate jdbc;

    /** 하루에 최초 유료 결제를 완료한 신청 수이다. */
    public record DailyCount(LocalDate date, long count) { }

    /**
     * 최초 승인부터 선택한 종료일까지 집계한다. 현재 유효한 신청이며 순납부액이 양수인 참가자만 포함한다.
     * 혼합 주문은 Allocation 목적을 사용하며 NULL 목적은 비혼합 부모 목적만 상속한다.
     * 직접 귀속 보완은 동일 결제·신청 Allocation이 없는 개인 최초 주문에만 적용한다.
     * 날짜 필터는 신청별 MIN 이후에 적용하여 최초 결제일이 범위 밖인 재결제를 다시 세지 않는다.
     */
    public List<DailyCount> findDailyCounts(String eventId, LocalDateTime openedAt,
                                           LocalDateTime endExclusive, int offsetMinutes) {
        String sql = PaymentReportSql.FIRST_PAYMENTS_CTE + """
                select date(first_at) as paid_date, count(*) as participant_count
                from first_payments f
                join registration r on r.id = f.registration_id
                where
                """ + PaymentReportSql.CURRENT_VALID + """
                  and r.paid_amount > 0
                  and first_at >= :openedAt and first_at < :endExclusive
                group by date(first_at)
                order by paid_date
                """;
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("eventId", eventId).addValue("openedAt", openedAt)
                .addValue("endExclusive", endExclusive).addValue("offsetMinutes", offsetMinutes);
        return jdbc.query(sql, parameters, (result, index) -> new DailyCount(
                result.getDate("paid_date").toLocalDate(), result.getLong("participant_count")));
    }
}
