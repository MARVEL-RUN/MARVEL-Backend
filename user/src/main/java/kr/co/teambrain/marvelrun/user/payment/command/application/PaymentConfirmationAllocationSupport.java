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
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationCommandRepository;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.support.OrganizationLockSupport;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.PaymentAllocation;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 승인 트랜잭션의 잠금 순서와 참가자별 납부·예약 검증을 공통으로 제공한다. */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class PaymentConfirmationAllocationSupport {
    private final EntityManager entityManager;
    private final PaymentCommandRepository paymentRepository;
    private final ReservationCommandRepository reservationRepository;
    private final PaymentRefundConflictGuard refundConflictGuard;

    /** 승인 시작 대상을 찾아 대회부터 잠그고 미확정 결제·환불과의 충돌을 차단한다. */
    public Payment lockForStart(String orderId) {
        Payment observed = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new CustomException(ErrorCode.PAYMENT_NOT_FOUND));
        List<Payment> locked = lockScope(observed);
        Payment current = requiredPayment(locked, observed.getId());
        for (Payment other : locked) {
            if (!Objects.equals(other.getId(), current.getId())
                    && (other.getProcessStatus() == PaymentProcessStatus.CONFIRMING
                        || other.getProcessStatus() == PaymentProcessStatus.UNKNOWN)) {
                throw new CustomException(ErrorCode.PAYMENT_NOT_CONFIRMABLE);
            }
        }
        refundConflictGuard.validate(locked);
        return current;
    }

    /** 성공·명확한 실패 반영도 같은 순서로 잠근다. 자신의 미확정 상태는 결과 반영을 막지 않는다. */
    public Payment lockForResult(String paymentId) {
        Payment observed = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new CustomException(ErrorCode.PAYMENT_NOT_FOUND));
        return requiredPayment(lockScope(observed), paymentId);
    }

    /**
     * 대회 → 단체 NOWAIT → 관련 Payment ID 순서로 잠근다.
     *
     * 사전 조회한 Payment는 잠금 범위를 확인하는 용도로만 사용한다.
     * 아직 변경하지 않은 해당 엔티티만 분리하여, 잠금 조회 시
     * 오래된 버전과 충돌하지 않고 최신 주문 상태를 읽도록 한다.
     */
    private List<Payment> lockScope(Payment payment) {
        if (payment.isRegistrationPayment() == payment.isOrgPayment()) {
            throw new CustomException(ErrorCode.PAYMENT_NOT_CONFIRMABLE);
        }

        boolean organizationPayment = payment.isOrgPayment();

        String organizationId = organizationPayment
                ? payment.getOrganization().getId()
                : null;

        String registrationId = organizationPayment
                ? null
                : payment.getRegistration().getId();

        Event event = organizationPayment
                ? payment.getOrganization().getEvent()
                : payment.getRegistration().getEvent();

        String eventId = event.getId();

        /*
         * 이 시점의 Payment는 범위 확인을 위해 조회했으며 변경하지 않았다.
         * 해당 Payment만 분리하고, 다른 엔티티의 영속 상태는 유지한다.
         */
        entityManager.detach(payment);

        entityManager.refresh(event, LockModeType.PESSIMISTIC_WRITE);

        List<Payment> locked;

        if (organizationPayment) {
            OrganizationLockSupport.lockWithoutWaiting(
                    entityManager,
                    organizationId
            );

            locked = paymentRepository
                    .findAllForOrganizationModificationForUpdate(
                            eventId,
                            organizationId
                    );
        } else {
            locked = paymentRepository
                    .findAllForPersonalModificationForUpdate(
                            eventId,
                            registrationId
                    );
        }

        for (Payment current : locked) {
            entityManager.refresh(current, LockModeType.PESSIMISTIC_WRITE);
        }

        return locked;
    }

    /** 잠근 범위에 실제 요청 주문이 없으면 진행하지 않는다. */
    private Payment requiredPayment(List<Payment> payments, String id) {
        return payments.stream().filter(payment -> Objects.equals(payment.getId(), id)).findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.PAYMENT_NOT_FOUND));
    }

    /**
     * 변경 가능한 신청을 최신 잠금 상태로 읽고 귀속 금액과 현재 미납액을 대조한다.
     * 최초 귀속만 HELD/PROCESSING을 요구하고 추가 귀속은 CONSUMED를 그대로 유지한다.
     * 완료 주문 재호출은 이 검증 전에 기존 결과를 반환해야 한다.
     */
    public List<String> validateAndGetInitialIds(
            Payment payment, List<PaymentAllocation> allocations, ReservationStatus initialStatus) {
        if (allocations == null || allocations.isEmpty()) {
            throw invalid();
        }
        Set<String> ids = new TreeSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        boolean hasInitial = false;
        boolean hasAdditional = false;
        for (PaymentAllocation allocation : allocations) {
            if (allocation == null || allocation.getRegistration() == null
                    || allocation.getRegistration().getId() == null) {
                throw invalid();
            }
        }
        List<PaymentAllocation> ordered = new ArrayList<>(allocations);
        ordered.sort(Comparator.comparing(allocation -> allocation.getRegistration().getId()));
        for (PaymentAllocation allocation : ordered) {
            Registration registration = allocation.getRegistration();
            if (allocation.getPayment() == null
                    || !Objects.equals(allocation.getPayment().getId(), payment.getId())
                    || registration == null || registration.getId() == null
                    || !ids.add(registration.getId()) || allocation.getAllocatedAmount() == null
                    || allocation.getAllocatedAmount().signum() < 0) {
                throw invalid();
            }
            entityManager.refresh(registration, LockModeType.PESSIMISTIC_WRITE);
            PaymentPurpose purpose = allocation.effectivePurpose();
            hasInitial |= purpose == PaymentPurpose.REGISTRATION_TRY;
            hasAdditional |= purpose == PaymentPurpose.ADDITIONAL_PAYMENT;
            sum = sum.add(allocation.getAllocatedAmount());
            validateRegistration(payment, allocation);
        }
        if (payment.getAmount() == null || payment.getAmount().signum() <= 0
                || sum.compareTo(payment.getAmount()) != 0
                || (payment.getPurpose() == PaymentPurpose.MIXED_PAYMENT
                    && (!payment.isOrgPayment() || !hasInitial || !hasAdditional
                        || allocations.stream().anyMatch(a -> a.getAllocatedAmount().signum() <= 0)))) {
            throw invalid();
        }
        Map<String, Reservation> byRegistration = new HashMap<>();
        List<Reservation> reservations = new ArrayList<>(reservationRepository.findAllByRegistrationIds(ids));
        reservations.sort(Comparator.comparing(Reservation::getId));
        for (Reservation reservation : reservations) {
            entityManager.refresh(reservation, LockModeType.PESSIMISTIC_WRITE);
            if (byRegistration.putIfAbsent(reservation.getRegistration().getId(), reservation) != null) {
                throw invalid();
            }
        }
        if (!byRegistration.keySet().equals(ids)) {
            throw new CustomException(ErrorCode.RESERVATION_NOT_FOUND);
        }
        List<String> initialIds = new ArrayList<>();
        for (PaymentAllocation allocation : ordered) {
            String id = allocation.getRegistration().getId();
            boolean initial = allocation.effectivePurpose() == PaymentPurpose.REGISTRATION_TRY;
            ReservationStatus expected = initial ? initialStatus : ReservationStatus.CONSUMED;
            if (byRegistration.get(id).getStatus() != expected) {
                throw new CustomException(ErrorCode.RESERVATION_STATE_CONFLICT);
            }
            if (initial) {
                initialIds.add(id);
            }
        }
        return List.copyOf(initialIds);
    }

    /** 타 대회·타 단체 귀속, 삭제 신청, 오래된 금액 및 잘못된 신청 상태를 거절한다. */
    private void validateRegistration(Payment payment, PaymentAllocation allocation) {
        Registration registration = allocation.getRegistration();
        Event event = payment.isOrgPayment()
                ? payment.getOrganization().getEvent() : payment.getRegistration().getEvent();
        if (registration.isSoftDeleted()
                || !Objects.equals(registration.getEvent().getId(), event.getId())
                || (payment.isRegistrationPayment()
                    && !Objects.equals(payment.getRegistration().getId(), registration.getId()))
                || (payment.isOrgPayment() && (registration.getOrganization() == null
                    || !Objects.equals(payment.getOrganization().getId(), registration.getOrganization().getId())))
                || registration.getContractAmount() == null || registration.getContractAmount().signum() < 0
                || registration.getPaidAmount() == null || registration.getPaidAmount().signum() < 0
                || registration.getContractAmount().subtract(registration.getPaidAmount())
                    .compareTo(allocation.getAllocatedAmount()) != 0) {
            throw invalid();
        }
        boolean initial = allocation.effectivePurpose() == PaymentPurpose.REGISTRATION_TRY;
        if ((initial && (registration.getPaidAmount().signum() != 0
                    || registration.getStatus() != RegistrationStatus.PAYMENT_PENDING))
                || (!initial && (allocation.getAllocatedAmount().signum() <= 0
                    || registration.getStatus() != RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED))) {
            throw invalid();
        }
    }

    /** 결제 귀속과 현재 신청의 불일치를 기존 업무 오류로 표현한다. */
    private CustomException invalid() {
        return new CustomException(ErrorCode.PAYMENT_ALLOCATION_INTEGRITY_ERROR);
    }
}
