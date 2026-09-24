package kr.co.teambrain.marvelrun.admin.event.query.repository;

import kr.co.teambrain.marvelrun.admin.event.query.dto.PaymentDailyCountRow;
import kr.co.teambrain.marvelrun.admin.event.query.report.RegistrationDailyReportRow;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 일별 신청·결제 보고서 전용 조회 Repository다.
 *
 * Payment / PaymentAllocation을 신청별로 먼저 집계하여
 * Registration마다 금융 테이블을 반복 탐색하는 correlated subquery를 제거한다.
 */
@Repository
@RequiredArgsConstructor
public class RegistrationDailyReportQueryRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * 보고서 대상 Registration을 먼저 제한한 뒤,
     * 직접 결제와 Allocation 결제를 registration_id 기준으로 평탄화한다.
     *
     * normal_first_payment:
     * 정상 금융 원장이 존재하는 신청자의 최초 COMPLETED 결제일.
     *
     * allocation_exists:
     * legacy fallback에서 "Allocation 자체가 없어야 한다"는 기존 조건 보존용.
     *
     * legacy_single_payment:
     * COMPLETED 단체 Payment가 정확히 1건인 단체만 조회한다.
     *
     * resolved_registration:
     * 정상 금융 원장을 우선 사용하고 없을 때만 기존 legacy 조건을 적용한다.
     */
    private static final String FINANCIAL_CTE = """
                WITH target_registration AS (
                     SELECT ...
                     FROM registration
                     WHERE event_id = ?
                 ),
                 
                 first_payment AS (
                     SELECT
                         registration_id,
                         MIN(paid_at) AS first_paid_at
                     FROM (
                         /* direct */
                         SELECT
                             p.registration_id,
                             p.created_at AS paid_at
                         FROM payment p
                         JOIN target_registration r
                           ON r.id = p.registration_id
                         WHERE p.process_status = 'COMPLETED'
                 
                         UNION ALL
                 
                         /* allocation */
                         SELECT
                             pa.registration_id,
                             p.created_at
                         FROM payment_allocation pa
                         JOIN payment p
                           ON p.id = pa.payment_id
                         JOIN target_registration r
                           ON r.id = pa.registration_id
                         WHERE p.process_status = 'COMPLETED'
                     ) x
                     GROUP BY registration_id
                 ),
                 
                 allocation_exists AS (
                     SELECT DISTINCT pa.registration_id
                     FROM payment_allocation pa
                     JOIN target_registration r
                       ON r.id = pa.registration_id
                 ),
                 
                 legacy_payment AS (
                     SELECT
                         p.organization_id,
                         MIN(p.created_at) AS first_paid_at
                     FROM payment p
                     WHERE p.process_status = 'COMPLETED'
                       AND p.organization_id IS NOT NULL
                     GROUP BY p.organization_id
                     HAVING COUNT(*) = 1
                 )
                 
                 SELECT ...
                 FROM target_registration r
                 LEFT JOIN first_payment fp
                        ON fp.registration_id = r.id
                 LEFT JOIN allocation_exists ae
                        ON ae.registration_id = r.id
                 LEFT JOIN legacy_payment lp
                        ON lp.organization_id = r.organization_id;
            """;

    /**
     * 엑셀 집계용 신청자별 데이터를 조회한다.
     */
    public List<RegistrationDailyReportRow> findReportRows(
            String eventId,
            LocalDateTime registrationStart,
            LocalDateTime endExclusive
    ) {

        String sql = FINANCIAL_CTE + """
            SELECT
                resolved.status,
                resolved.birth,
                category.name AS course_name,
                resolved.paid_amount,
                resolved.registration_date,
                resolved.first_paid_at
            FROM resolved_registration resolved
            INNER JOIN event_category category
                ON category.id = resolved.event_category_id
            ORDER BY resolved.registration_date, resolved.id
            """;

        MapSqlParameterSource parameters =
                parameters(
                        eventId,
                        registrationStart,
                        endExclusive
                );

        return jdbcTemplate.query(
                sql,
                parameters,
                (resultSet, rowNumber) ->
                        new RegistrationDailyReportRow(
                                RegistrationStatus.valueOf(
                                        resultSet.getString("status")
                                ),
                                resultSet.getString("birth"),
                                resultSet.getString("course_name"),
                                resultSet.getBigDecimal("paid_amount"),
                                localDateTime(
                                        resultSet.getTimestamp(
                                                "registration_date"
                                        )
                                ),
                                localDateTime(
                                        resultSet.getTimestamp(
                                                "first_paid_at"
                                        )
                                )
                        )
        );
    }

    /**
     * 그래프용 결제자 수를 DB에서 최초 결제일별로 직접 집계한다.
     *
     * Java로 전체 Registration을 가져오지 않는다.
     */
    public List<PaymentDailyCountRow> findPaymentDailyCounts(
            String eventId,
            LocalDateTime registrationStart,
            LocalDateTime endExclusive
    ) {

        String sql = FINANCIAL_CTE + """
            SELECT
                DATE(resolved.first_paid_at) AS paid_date,
                COUNT(*) AS daily_count
            FROM resolved_registration resolved
            WHERE resolved.first_paid_at IS NOT NULL

              /*
               * 현재 순납부액이 존재하는 사람만 현재 유효 결제자다.
               */
              AND resolved.paid_amount > 0

              /*
               * 현재 그래프의 기존 isCurrentPayer() 정책을 유지한다.
               */
              AND resolved.status NOT IN (
                  'CANCELED',
                  'EXPIRED'
              )

              /*
               * 접수 시작 이후부터 조회 종료일까지의 최초 결제만 사용한다.
               */
              AND resolved.first_paid_at >= :registrationStart
              AND resolved.first_paid_at < :endExclusive

            GROUP BY DATE(resolved.first_paid_at)
            ORDER BY paid_date
            """;

        MapSqlParameterSource parameters =
                parameters(
                        eventId,
                        registrationStart,
                        endExclusive
                );

        return jdbcTemplate.query(
                sql,
                parameters,
                (resultSet, rowNumber) ->
                        new PaymentDailyCountRow(
                                resultSet
                                        .getDate("paid_date")
                                        .toLocalDate(),
                                resultSet.getLong("daily_count")
                        )
        );
    }

    /**
     * 공통 보고서 SQL 파라미터를 생성한다.
     */
    private MapSqlParameterSource parameters(
            String eventId,
            LocalDateTime registrationStart,
            LocalDateTime endExclusive
    ) {

        return new MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue(
                        "registrationStart",
                        registrationStart
                )
                .addValue(
                        "endExclusive",
                        endExclusive
                );
    }

    /**
     * nullable SQL Timestamp를 LocalDateTime으로 변환한다.
     */
    private LocalDateTime localDateTime(
            Timestamp timestamp
    ) {

        return timestamp == null
                ? null
                : timestamp.toLocalDateTime();
    }
}