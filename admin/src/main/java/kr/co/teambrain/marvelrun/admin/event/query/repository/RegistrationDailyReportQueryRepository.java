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
    WITH event_registrations AS (
        SELECT
            r.id,
            r.status,
            r.birth,
            r.event_category_id,
            r.organization_id,
            r.contract_amount,
            r.paid_amount,
            r.registration_date
        FROM registration r
        WHERE r.event_id = :eventId
          AND r.is_del = 0
          AND r.registration_date >= :registrationStart
          AND r.registration_date < :endExclusive
    ),

    payment_sources AS (
        /**
         * 개인 직접 귀속 COMPLETED Payment.
         */
        SELECT
            p.registration_id AS registration_id,
            p.created_at AS paid_at
        FROM payment p
        INNER JOIN event_registrations r
            ON r.id = p.registration_id
        WHERE p.process_status = 'COMPLETED'
          AND p.registration_id IS NOT NULL

        UNION ALL

        /**
         * PaymentAllocation 귀속 COMPLETED Payment.
         *
         * 동일 Payment가 직접 귀속과 Allocation 양쪽에 존재하더라도
         * 이후 MIN()에서 하나의 최초 결제일로 수렴한다.
         */
        SELECT
            pa.registration_id AS registration_id,
            p.created_at AS paid_at
        FROM payment_allocation pa
        INNER JOIN event_registrations r
            ON r.id = pa.registration_id
        INNER JOIN payment p
            ON p.id = pa.payment_id
        WHERE p.process_status = 'COMPLETED'
    ),

    normal_first_payment AS (
        /**
         * 정상 금융 귀속이 확인되는 신청자의 최초 완료 결제일이다.
         */
        SELECT
            source.registration_id,
            MIN(source.paid_at) AS first_paid_at
        FROM payment_sources source
        GROUP BY source.registration_id
    ),

    allocation_exists AS (
        /**
         * 기존 legacy fallback 규칙상
         * 완료 여부와 관계없이 Allocation 자체가 존재하면
         * 단체 결제를 추정하지 않는다.
         */
        SELECT DISTINCT
            pa.registration_id
        FROM payment_allocation pa
        INNER JOIN event_registrations r
            ON r.id = pa.registration_id
    ),

    event_organizations AS (
        /**
         * 현재 조회 대상 Registration에 존재하는 단체만
         * legacy Payment 조회 대상으로 제한한다.
         */
        SELECT DISTINCT
            r.organization_id
        FROM event_registrations r
        WHERE r.organization_id IS NOT NULL
    ),

    legacy_single_payment AS (
        /**
         * 기존 findStatsByEventId와 동일하게
         * COMPLETED 단체 Payment가 정확히 1건인 단체만 허용한다.
         */
        SELECT
            p.organization_id,
            MIN(p.created_at) AS first_paid_at
        FROM payment p
        INNER JOIN event_organizations eo
            ON eo.organization_id = p.organization_id
        WHERE p.process_status = 'COMPLETED'
        GROUP BY p.organization_id
        HAVING COUNT(p.id) = 1
    ),

    resolved_registration AS (
        SELECT
            r.id,
            r.status,
            r.birth,
            r.event_category_id,
            r.organization_id,
            r.contract_amount,
            r.paid_amount,
            r.registration_date,

            COALESCE(
                normal.first_paid_at,

                CASE
                    /**
                     * 기존 legacy fallback 조건을 그대로 유지한다.
                     *
                     * normal이 null:
                     * 직접 COMPLETED Payment도 없고
                     * Allocation 귀속 COMPLETED Payment도 없음.
                     *
                     * allocation이 null:
                     * PaymentAllocation 자체도 없음.
                     */
                    WHEN normal.registration_id IS NULL
                     AND allocation.registration_id IS NULL
                     AND r.status = 'CONFIRMED'
                     AND r.contract_amount > 0
                     AND r.paid_amount >= r.contract_amount
                    THEN legacy.first_paid_at
                    ELSE NULL
                END
            ) AS first_paid_at

        FROM event_registrations r

        LEFT JOIN normal_first_payment normal
            ON normal.registration_id = r.id

        LEFT JOIN allocation_exists allocation
            ON allocation.registration_id = r.id

        LEFT JOIN legacy_single_payment legacy
            ON legacy.organization_id = r.organization_id
    )
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

          /**
           * 현재 순납부액이 존재하는 사람만 현재 유효 결제자다.
           */
          AND resolved.paid_amount > 0

          /**
           * 현재 보고서 기준 유효 신청 상태만 포함한다.
           */
          AND resolved.status NOT IN (
              'CANCELLATION_PENDING',
              'CANCELED',
              'EXPIRED'
          )

          /**
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