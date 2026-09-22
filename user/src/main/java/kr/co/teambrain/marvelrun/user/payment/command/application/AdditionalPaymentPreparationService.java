package kr.co.teambrain.marvelrun.user.payment.command.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.*;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult.Order;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.support.OrganizationLockSupport;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationAccessVerifier;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentAllocationCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.PaymentAllocation;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentAllocationCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentAllocationTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기존 주문 ID가 없어도 현재 확정 신청의 추가 납부 주문을 본인확인 후 준비한다.
 * 대회 → 단체 → 결제/취소 → 신청 → 예약 순으로 잠그며 실제 승인/정원 재확보는 하지 않는다.
 * 금액·명단은 클라이언트에서 받지 않고 DB의 양수 부족액으로 확정한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AdditionalPaymentPreparationService {
    private final EntityManager entityManager;
    private final PaymentCommandRepository payments;
    private final PaymentAllocationCommandRepository allocations;
    private final PaymentRefundConflictGuard refunds;
    private final PaymentConfirmationAllocationSupport confirmation;
    private final PaymentCreator creator;
    private final PaymentAllocationCreator allocationCreator;

    /** 생년월일이 정정되었다면 정정 후 현재 값으로 본인확인한다. */
    public Order preparePersonal(String eventId, String registrationId, RegistrationAccessRequest access) {
        lockEvent(eventId);
        List<Payment> locked = lockPayments(eventId, null, registrationId);
        Registration registration = current(Registration.class, registrationId);
        if (!eventId.equals(registration.getEvent().getId()) || registration.getOrganization() != null) {
            throw new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED);
        }
        RegistrationAccessVerifier.verifyPersonal(registration, access);
        return prepare(locked, List.of(registration), null);
    }

    /** 단체장은 확정된 구성원 중 추가 납부가 남은 인원들의 금액만 한 주문으로 납부한다. */
    public Order prepareOrganization(String eventId, String organizationId, OrganizationAccessRequest access) {
        lockEvent(eventId);
        Organization organization = entityManager.find(Organization.class, organizationId);
        if (organization == null) { throw new CustomException(ErrorCode.ORGANIZATION_ACCESS_DENIED); }
        OrganizationLockSupport.lockWithoutWaiting(entityManager, organizationId);
        entityManager.refresh(organization, LockModeType.PESSIMISTIC_WRITE);
        if (!eventId.equals(organization.getEvent().getId())) {
            throw new CustomException(ErrorCode.ORGANIZATION_ACCESS_DENIED);
        }
        RegistrationAccessVerifier.verifyOrganization(organization, access);
        List<Payment> locked = lockPayments(eventId, organizationId, null);
        List<Registration> targets = entityManager.createQuery("""
                select r from Registration r where r.event.id=:event and r.organization.id=:org
                  and r.softDeleted=false and r.status=:state order by r.id
                """, Registration.class).setParameter("event", eventId).setParameter("org", organizationId)
                .setParameter("state", RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED)
                .setMaxResults(101).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        if (targets.size() > 100) {
            throw new CustomException(ErrorCode.PAYMENT_NOT_CONFIRMABLE, " 추가 결제 대상이 100명을 초과합니다. 관리자에게 문의해 주세요.");
        }
        for (Registration target : targets) { entityManager.refresh(target, LockModeType.PESSIMISTIC_WRITE); }
        return prepare(locked, targets, organization);
    }

    /** 진행 중 거래는 차단하고 잠금 현재값만 사용한다. */
    private List<Payment> lockPayments(String eventId, String organizationId, String registrationId) {
        List<Payment> locked = organizationId == null
                ? payments.findAllForPersonalModificationForUpdate(eventId, registrationId)
                : payments.findAllForOrganizationModificationForUpdate(eventId, organizationId);
        if (locked.size() > 500) { throw new CustomException(ErrorCode.PAYMENT_NOT_CONFIRMABLE); }
        for (Payment payment : locked) {
            entityManager.refresh(payment, LockModeType.PESSIMISTIC_WRITE);
            payment.validateRegistrationModificationAllowed();
        }
        refunds.validate(locked);
        return locked;
    }

    /** 같은 부족액의 READY 주문은 재사용한다. 다른 READY 주문이 있으면 임의 폐기하지 않는다. */
    private Order prepare(List<Payment> locked, List<Registration> registrations, Organization organization) {
        if (registrations.isEmpty()) { throw new CustomException(ErrorCode.PAYMENT_NOT_CONFIRMABLE, " 추가 납부할 금액이 없습니다."); }
        Map<String, BigDecimal> due = new TreeMap<>();
        for (Registration row : registrations) {
            if (row.isSoftDeleted() || row.getStatus() != RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED
                    || row.getContractAmount() == null || row.getPaidAmount() == null || row.getPaidAmount().signum() < 0
                    || row.getContractAmount().compareTo(row.getPaidAmount()) <= 0
                    || !Objects.equals(organization == null ? null : organization.getId(),
                            row.getOrganization() == null ? null : row.getOrganization().getId())) {
                throw new CustomException(ErrorCode.REGISTRATION_FINANCIAL_STATE_INVALID);
            }
            due.put(row.getId(), row.getContractAmount().subtract(row.getPaidAmount()));
        }
        List<Reservation> reservations = entityManager.createQuery("""
                select v from Reservation v where v.registration.id in :ids order by v.id
                """, Reservation.class).setParameter("ids", due.keySet())
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        Set<String> reserved = new HashSet<>();
        for (Reservation reservation : reservations) {
            entityManager.refresh(reservation, LockModeType.PESSIMISTIC_WRITE);
            if (reservation.getStatus() != ReservationStatus.CONSUMED || !reserved.add(reservation.getRegistration().getId())) {
                throw new CustomException(ErrorCode.RESERVATION_STATE_CONFLICT);
            }
        }
        if (!reserved.equals(due.keySet())) { throw new CustomException(ErrorCode.RESERVATION_NOT_FOUND); }
        List<Payment> ready = locked.stream().filter(p -> p.getProcessStatus() == PaymentProcessStatus.READY).toList();
        if (!ready.isEmpty()) {
            if (ready.size() != 1 || !matches(ready.getFirst(), due)) {
                throw new CustomException(ErrorCode.PAYMENT_NOT_CONFIRMABLE, " 다른 결제 대기 주문이 있습니다. 기존 주문을 확인해 주세요.");
            }
            Payment reusable = ready.getFirst();
            confirmation.validateForPreparation(reusable, allocations.findAllForPaymentUpdate(reusable.getId()));
            return response(reusable);
        }
        BigDecimal amount = due.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        String correlationId = UUID.randomUUID().toString();
        Payment created = organization == null
                ? creator.createAdditionalPayment(registrations.getFirst(), amount, correlationId)
                : creator.createAdditionalPayment(organization, amount, correlationId);
        List<PaymentAllocationTarget> shares = registrations.stream()
                .map(r -> new PaymentAllocationTarget(r, due.get(r.getId()), PaymentPurpose.ADDITIONAL_PAYMENT)).toList();
        allocationCreator.create(created, shares);
        entityManager.flush();
        confirmation.validateForPreparation(created, allocations.findAllForPaymentUpdate(created.getId()));
        return response(created);
    }

    /** 추가 주문 목적·소유권·귀속 전체·금액이 동일해야 재사용한다. */
    private boolean matches(Payment payment, Map<String, BigDecimal> due) {
        if (payment.getPurpose() != PaymentPurpose.ADDITIONAL_PAYMENT) { return false; }
        List<PaymentAllocation> shares = allocations.findAllForPaymentUpdate(payment.getId());
        Map<String, BigDecimal> actual = new TreeMap<>();
        for (PaymentAllocation share : shares) {
            if (share.effectivePurpose() != PaymentPurpose.ADDITIONAL_PAYMENT || share.getRegistration() == null
                    || share.getAllocatedAmount() == null
                    || actual.putIfAbsent(share.getRegistration().getId(), share.getAllocatedAmount()) != null) { return false; }
        }
        return actual.keySet().equals(due.keySet())
                && due.entrySet().stream().allMatch(e -> e.getValue().compareTo(actual.get(e.getKey())) == 0)
                && payment.getAmount() != null
                && payment.getAmount().compareTo(due.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)) == 0;
    }

    /** 신규 신청 정책을 재적용하지 않고 기존 확정 참가의 금융 업무만 직렬화한다. */
    private void lockEvent(String eventId) {
        Event event = entityManager.find(Event.class, eventId);
        if (event == null) { throw new CustomException(ErrorCode.EVENT_NOT_FOUND); }
        entityManager.refresh(event, LockModeType.PESSIMISTIC_WRITE);
    }

    /** 잠금 이후 1차 캐시를 최신 DB 상태로 갱신한다. */
    private <T> T current(Class<T> type, String id) {
        T value = entityManager.find(type, id);
        if (value == null) { throw new CustomException(ErrorCode.REGISTRATION_NOT_FOUND); }
        entityManager.refresh(value, LockModeType.PESSIMISTIC_WRITE);
        return value;
    }

    /** 기존 결제창 응답 계약을 그대로 사용한다. */
    private Order response(Payment payment) {
        return new Order(payment.getId(), payment.getOrderId(), payment.getOrderName(), payment.getAmount());
    }
}
