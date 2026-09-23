package kr.co.teambrain.marvelrun.admin.event.query.support;

/** 그래프와 엑셀에서 동일한 최초 참가비 승인 귀속 및 현재 유효 신청 조건을 사용한다. */
public final class PaymentReportSql {
    /** 객체 상태를 가지지 않는 조회 SQL 정의이다. */
    private PaymentReportSql() { }

    /** 완료된 최초 참가비 귀속과 승인 시각을 분리한다. 시각 누락 결제도 전액환불 판정 증거로 남긴다. */
    public static final String FIRST_PAYMENTS_CTE = """
                with candidates as (
                    select a.registration_id, p.approved_at
                    from payment_allocation a
                    join payment p on p.id = a.payment_id
                    join registration r on r.id = a.registration_id
                    where r.event_id = :eventId
                      and p.process_status = 'COMPLETED'
                      and p.amount > 0 and a.allocated_amount > 0
                      and coalesce(a.allocation_purpose,
                          case when p.purpose <> 'MIXED_PAYMENT' then p.purpose end) = 'REGISTRATION_TRY'
                    union all
                    select r.id, p.approved_at
                    from payment p
                    join registration r on r.id = p.registration_id
                    where r.event_id = :eventId
                      and p.process_status = 'COMPLETED'
                      and p.amount > 0
                      and p.purpose = 'REGISTRATION_TRY'
                      and not exists (
                          select 1 from payment_allocation a
                          where a.payment_id = p.id and a.registration_id = r.id
                      )
                ), first_payments as (
                    select registration_id,
                           timestampadd(MINUTE, :offsetMinutes, min(approved_at)) as first_at
                    from candidates
                    group by registration_id
                    having min(approved_at) is not null
                )
                """;

    /** r 별칭의 현재 신청 중 삭제·취소접수·취소완료·만료를 제외한다. */
    public static final String CURRENT_VALID = """
                r.is_del = false
                and r.status not in ('CANCELLATION_PENDING', 'CANCELED', 'EXPIRED')
                """;
}
