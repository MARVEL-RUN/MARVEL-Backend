package kr.co.teambrain.marvelrun.admin.event.query.report;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import kr.co.teambrain.marvelrun.admin.event.query.support.PaymentReportSql;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 신청일과 최초 결제일을 별도로 집계하여 코스·아동 여부별 소량 결과만 반환한다. */
@Repository
@RequiredArgsConstructor
public class RegistrationDailyReportRepository {
    private final NamedParameterJdbcTemplate jdbc;

    /** 특정 날짜·코스·아동 구분의 신청자 수 및 결제자 수이다. */
    public record Aggregate(LocalDate date, String courseId, boolean child, long applicants, long paid) { }

    /**
     * 현재 분류와 유효 상태를 적용한다. 신청일은 KST, 승인 시각은 공통 CTE에서 KST로 변환한다.
     * 생년월일 분류는 기존 statistics처럼 숫자 8자리 및 20131101 이상을 아동으로 판정한다.
     * 두 집계를 단일 SQL로 읽어 결제·취소 동시 변경으로 서로 다른 상태가 섞이는 것을 줄인다.
     */
    public List<Aggregate> aggregate(String eventId, LocalDateTime openedAt,
                                     LocalDateTime endExclusive, int offsetMinutes) {
        String sql = PaymentReportSql.FIRST_PAYMENTS_CTE + """
                , eligible as (
                    select r.id, r.event_category_id, r.registration_date, r.paid_amount,
                           case when char_length(regexp_replace(coalesce(r.birth,''),'[^0-9]','')) = 8
                                 and regexp_replace(coalesce(r.birth,''),'[^0-9]','') >= '20131101'
                                then 1 else 0 end as child_flag
                    from registration r
                    where r.event_id = :eventId and
                """ + PaymentReportSql.CURRENT_VALID + """
                    and (r.paid_amount > 0 or not exists (
                        select 1 from candidates history where history.registration_id = r.id
                    ))
                ), movements as (
                    select date(e.registration_date) as source_date, e.event_category_id, e.child_flag,
                           count(*) as applicants, cast(0 as signed) as paid
                    from eligible e
                    where e.registration_date >= :openedAt and e.registration_date < :endExclusive
                    group by date(e.registration_date), e.event_category_id, e.child_flag
                    union all
                    select date(f.first_at), e.event_category_id, e.child_flag,
                           cast(0 as signed), count(*)
                    from eligible e join first_payments f on f.registration_id = e.id
                    where e.paid_amount > 0 and f.first_at >= :openedAt and f.first_at < :endExclusive
                    group by date(f.first_at), e.event_category_id, e.child_flag
                )
                select source_date, event_category_id, child_flag,
                       sum(applicants) as applicants, sum(paid) as paid
                from movements
                group by source_date, event_category_id, child_flag
                order by source_date, event_category_id, child_flag
                """;
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("eventId",eventId).addValue("openedAt",openedAt)
                .addValue("endExclusive",endExclusive).addValue("offsetMinutes",offsetMinutes);
        return jdbc.query(sql, parameters, (result, index) -> new Aggregate(
                result.getDate("source_date").toLocalDate(), result.getString("event_category_id"),
                result.getBoolean("child_flag"), result.getLong("applicants"), result.getLong("paid")));
    }
}
