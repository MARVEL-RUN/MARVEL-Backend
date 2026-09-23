package kr.co.teambrain.marvelrun.admin.payment.command;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.admin.payment.command.AdminRefundLockedScope.*;

/** 사용자 원장 조회 범위를 유지한다. 잠금 대기를 만들지 않고 MySQL NOWAIT 충돌만 변환한다. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
@RequiredArgsConstructor
public class AdminRefundLockRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public static final int MAX_LEDGER_ROWS = 500;

    /** 대회부터 잠근다. paymentDeadline 또는 접수기간 조건을 추가하지 않는다. */
    public boolean lockEvent(String eventId) {
        return !noWait(() -> jdbc.queryForList(
                "select id from event where id=:id for update nowait", Map.of("id", eventId), String.class)).isEmpty();
    }

    /** 단체 귀속을 확인하면서 사용자와 동일한 단체 NOWAIT 규칙을 적용한다. */
    public boolean lockOrganization(String eventId, String organizationId) {
        return !noWait(() -> jdbc.queryForList(
                "select id from organization where id=:id and event_id=:eventId for update nowait",
                Map.of("id", organizationId, "eventId", eventId), String.class)).isEmpty();
    }

    /** 삭제된 구성원 관련 주문까지 포함한다. 일부 구성원 선택도 단체 전체 금융 충돌을 검사한다. */
    public List<PaymentRow> lockPayments(String eventId, String organizationId, String registrationId) {
        String scope = organizationId == null ? """
                (exists(select 1 from registration r where r.id=p.registration_id
                    and r.id=:targetId and r.event_id=:eventId)
                 or exists(select 1 from payment_allocation a join registration r on r.id=a.registration_id
                    where a.payment_id=p.id and r.id=:targetId and r.event_id=:eventId))
                """ : """
                (exists(select 1 from organization o where o.id=p.organization_id
                    and o.id=:targetId and o.event_id=:eventId)
                 or exists(select 1 from registration r where r.id=p.registration_id
                    and r.organization_id=:targetId and r.event_id=:eventId)
                 or exists(select 1 from payment_allocation a join registration r on r.id=a.registration_id
                    where a.payment_id=p.id and r.organization_id=:targetId and r.event_id=:eventId))
                """;
        return noWait(() -> jdbc.query("select p.id, p.process_status from payment p where " + scope
                + " order by p.id limit 501 for update nowait",
                Map.of("eventId", eventId, "targetId", organizationId == null ? registrationId : organizationId),
                (rs, row) -> new PaymentRow(rs.getString("id"),
                        state(PaymentProcessStatus.class, rs.getString("process_status")))));
    }

    /** 부모 결제 잠금 이후 취소 행을 ID 순으로 확보한다. */
    public List<CancelRow> lockCancellations(List<String> paymentIds) {
        if (paymentIds.isEmpty()) { return List.of(); }
        return noWait(() -> jdbc.query("""
                select id, payment_id, status from payment_cancel where payment_id in (:ids)
                order by id limit 501 for update nowait
                """, Map.of("ids", paymentIds), (rs, row) -> new CancelRow(rs.getString("id"),
                rs.getString("payment_id"), state(PaymentCancelStatus.class, rs.getString("status")))));
    }

    /** 요청 명단을 잠금 후 재검증한다. 개인/단체 여부는 서비스가 정확히 비교한다. */
    public List<RegistrationRow> lockRegistrations(String eventId, List<String> ids) {
        return noWait(() -> jdbc.query("""
                select id, organization_id, is_del, status, contract_amount, paid_amount
                from registration where event_id=:eventId and id in (:ids)
                order by id for update nowait
                """, Map.of("eventId", eventId, "ids", ids), (rs, row) -> new RegistrationRow(
                rs.getString("id"), rs.getString("organization_id"), rs.getBoolean("is_del"),
                state(RegistrationStatus.class, rs.getString("status")),
                rs.getBigDecimal("contract_amount"), rs.getBigDecimal("paid_amount"))));
    }

    /** 알 수 없는 저장 상태를 임의 기본값으로 대체하지 않는다. */
    private static <E extends Enum<E>> E state(Class<E> type, String value) {
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException | NullPointerException exception) {
            throw new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
        }
    }

    /** 사용자 OrganizationLockSupport와 동일하게 MySQL 3572만 경합으로 변환한다. */
    private static <T> T noWait(Supplier<T> action) {
        try { return action.get(); }
        catch (RuntimeException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && sql.getErrorCode() == 3572) {
                    throw new CustomException(ErrorCode.CONCURRENT_MODIFICATION);
                }
            }
            throw exception;
        }
    }
}
