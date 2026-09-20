package kr.co.teambrain.marvelrun.user.payment.command.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Query;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.PaymentCancel;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.AdditionalPaymentScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 추가 주문 준비 범위를 MySQL 현재 읽기로 잠근 뒤 최신 엔티티를 적재한다.
 * Event → Organization(단체) → Payment → PaymentCancel → Registration → Reservation 순서다.
 * 대상 행은 NOWAIT로 먼저 잠그며 충돌 시 해당 준비 요청을 실패시킨다.
 * 이후 이미 확보한 행을 갱신한다. 다른 경로 전체의 데드락 방지를 보장하지는 않는다.
 * 이 순서를 기존 전체 프로젝트가 이미 준수한다고 가정하지 않는다.
 */
@Repository
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class AdditionalPaymentPreparationLoader {
    private final EntityManager entityManager;

    /** 개인 직접 신청의 금융 범위를 잠근다. 단체 소속 신청은 개인 경로에서 거절한다. */
    public AdditionalPaymentScope lockPersonal(String eventId, String registrationId) {
        return lockScope(eventId, registrationId, false);
    }

    /** 삭제 구성원의 과거 금융거래까지 포함해 단체 범위를 보호한다. */
    public AdditionalPaymentScope lockOrganization(String eventId, String organizationId) {
        return lockScope(eventId, organizationId, true);
    }

    /** 고정된 잠금 순서로 현재 행을 확인하고 영속성 컨텍스트의 오래된 값을 갱신한다. */
    private AdditionalPaymentScope lockScope(String eventId, String targetId, boolean group) {
        if (eventId == null || eventId.isBlank() || targetId == null || targetId.isBlank()) {
            throw new CustomException(ErrorCode.PAYMENT_ADJUSTMENT_TARGET_INVALID);
        }
        List<String> eventIds = ids("select id from event where id = :eventId for update nowait",
                "eventId", eventId);
        if (eventIds.isEmpty()) {
            throw new CustomException(ErrorCode.EVENT_NOT_FOUND);
        }
        Event event = current(Event.class, eventId);
        Organization organization = null;
        if (group) {
            List<String> organizationIds = ids(
                    "select id from organization where id = :targetId and event_id = :eventId for update nowait",
                    "targetId", targetId, "eventId", eventId);
            if (organizationIds.isEmpty()) {
                throw new CustomException(ErrorCode.ORGANIZATION_NOT_FOUND);
            }
            organization = current(Organization.class, targetId);
        }

        // 소속 조회에 삭제 조건을 붙이지 않아 과거 금융 처리도 보호 범위에 포함한다.
        String paymentSql = group ? """
                select p.id from payment p
                where p.organization_id = :targetId
                   or exists (select 1 from registration r
                              where r.id = p.registration_id and r.organization_id = :targetId)
                   or exists (select 1 from payment_allocation a
                              join registration r on r.id = a.registration_id
                              where a.payment_id = p.id and r.organization_id = :targetId)
                order by p.id for update nowait
                """ : """
                select p.id from payment p
                where p.registration_id = :targetId
                   or exists (select 1 from payment_allocation a
                              where a.payment_id = p.id and a.registration_id = :targetId)
                order by p.id for update nowait
                """;
        List<String> paymentIds = ids(paymentSql, "targetId", targetId);
        List<Payment> payments = entities(Payment.class, paymentIds);
        List<PaymentCancel> cancellations = paymentIds.isEmpty() ? List.of() : entities(
                PaymentCancel.class,
                ids("select id from payment_cancel where payment_id in (:ids) order by id for update nowait",
                        "ids", paymentIds));

        String registrationSql = group ? """
                select id from registration
                where event_id = :eventId and organization_id = :targetId and is_del = 0
                order by id for update nowait
                """ : """
                select id from registration
                where event_id = :eventId and id = :targetId and organization_id is null and is_del = 0
                order by id for update nowait
                """;
        List<String> registrationIds = ids(registrationSql, "eventId", eventId, "targetId", targetId);
        if (!group && registrationIds.isEmpty()) {
            throw new CustomException(ErrorCode.REGISTRATION_NOT_FOUND);
        }
        List<Registration> registrations = entities(Registration.class, registrationIds);
        List<Reservation> reservations = registrationIds.isEmpty() ? List.of() : entities(
                Reservation.class,
                ids("select id from reservation where registration_id in (:ids) order by id for update nowait",
                        "ids", registrationIds));
        return new AdditionalPaymentScope(event, organization, registrations, reservations,
                payments, cancellations);
    }

    /** 고정된 SQL만 실행하며 MySQL NOWAIT 충돌만 업무 예외로 변환한다. */
    private List<String> ids(String sql, Object... parameters) {
        try {
            Query query = entityManager.createNativeQuery(sql);
            for (int index = 0; index < parameters.length; index += 2) {
                query.setParameter((String) parameters[index], parameters[index + 1]);
            }
            List<?> rows = query.getResultList();
            List<String> result = new ArrayList<>();
            for (Object row : rows) {
                result.add((String) row);
            }
            return result;
        } catch (RuntimeException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && sql.getErrorCode() == 3572) {
                    throw new CustomException(ErrorCode.CONCURRENT_MODIFICATION);
                }
            }
            throw exception;
        }
    }

    /** 이미 잠근 대상을 같은 순서로 적재한다. */
    private <T> List<T> entities(Class<T> type, List<String> ids) {
        List<T> result = new ArrayList<>();
        for (String id : ids) {
            result.add(current(type, id));
        }
        return result;
    }

    /** 잠긴 행을 현재 읽기로 갱신하여 1차 캐시의 오래된 값을 사용하지 않는다. */
    private <T> T current(Class<T> type, String id) {
        T entity = entityManager.find(type, id);
        if (entity == null) {
            throw new CustomException(ErrorCode.PAYMENT_ADJUSTMENT_TARGET_INVALID);
        }
        entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        return entity;
    }
}
