package kr.co.teambrain.marvelrun.user.payment.command.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.RegistrationCapacityService;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult.Order;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationAccessVerifier;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentAllocationCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.PaymentAllocation;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentAllocationCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentAllocationTarget;
import kr.co.teambrain.marvelrun.user.payment.command.application.valid.EventPaymentPolicyValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 현재 본인확인과 불변 귀속을 기준으로 최초·추가·혼합 주문의 재결제를 한 주문으로 준비한다. */
@Service
@RequiredArgsConstructor
public class PaymentRetryPreparationService {
    private final PaymentConfirmationAllocationSupport support;
    private final PaymentAllocationCommandRepository allocationRepository;
    private final RegistrationCapacityService capacityService;
    private final PaymentCreator paymentCreator;
    private final PaymentAllocationCreator allocationCreator;
    private final EventPaymentPolicyValidator policyValidator;
    private final ServerTimeProvider time;
    private final EntityManager entityManager;

    /** 개인 주문의 소유권과 대회를 현재 DB 값으로 검증하고 결제창 정보를 반환한다. */
    @Transactional
    public Order preparePersonal(String eventId, String registrationId, String paymentId,
            RegistrationAccessRequest access) {
        List<Payment> locked = support.lockForPreparation(paymentId);
        Payment original = required(locked, paymentId);
        if (!original.isRegistrationPayment()
                || !Objects.equals(original.getRegistration().getId(), registrationId)) {
            throw new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED);
        }
        Registration registration = original.getRegistration();
        entityManager.refresh(registration, LockModeType.PESSIMISTIC_WRITE);
        if (registration.getOrganization() != null
                || !Objects.equals(registration.getEvent().getId(), eventId)) {
            throw new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED);
        }
        RegistrationAccessVerifier.verifyPersonal(registration, access);
        return prepare(original, locked, registration.getEvent());
    }

    /** 단체 인증 후 원 주문에 귀속된 참가자 전체를 함께 재준비하며 프론트의 금액·명단을 받지 않는다. */
    @Transactional
    public Order prepareOrganization(String eventId, String organizationId, String paymentId,
            OrganizationAccessRequest access) {
        List<Payment> locked = support.lockForPreparation(paymentId);
        Payment original = required(locked, paymentId);
        if (!original.isOrgPayment()
                || !Objects.equals(original.getOrganization().getId(), organizationId)) {
            throw new CustomException(ErrorCode.ORGANIZATION_ACCESS_DENIED);
        }
        entityManager.refresh(original.getOrganization(), LockModeType.PESSIMISTIC_WRITE);
        if (!Objects.equals(original.getOrganization().getEvent().getId(), eventId)) {
            throw new CustomException(ErrorCode.ORGANIZATION_ACCESS_DENIED);
        }
        RegistrationAccessVerifier.verifyOrganization(original.getOrganization(), access);
        return prepare(original, locked, original.getOrganization().getEvent());
    }

    /** 유효 READY 주문은 재사용하고, 명확히 실패·무효화된 주문만 같은 귀속으로 새로 준비한다. */
    private Order prepare(Payment original, List<Payment> locked, Event event) {
        PaymentProcessStatus status = original.getProcessStatus();
        if (status != PaymentProcessStatus.READY && status != PaymentProcessStatus.FAILED
                && status != PaymentProcessStatus.INVALIDATED) {
            throw new CustomException(ErrorCode.PAYMENT_NOT_CONFIRMABLE);
        }
        LocalDateTime now = time.currentDateTime();
        policyValidator.validateForPurpose(event, now, original.getPurpose());
        List<PaymentAllocation> originalAllocations = allocationRepository.findAllForPaymentUpdate(original.getId());
        List<String> initialIds = support.validateForPreparation(original, originalAllocations);
        Map<String, Share> expected = shares(originalAllocations);
        Payment reusable = null;
        for (Payment candidate : locked) {
            if (candidate.getProcessStatus() != PaymentProcessStatus.READY) {
                continue;
            }
            List<PaymentAllocation> candidateAllocations = allocationRepository.findAllForPaymentUpdate(candidate.getId());
            if (candidateAllocations.isEmpty()) {
                throw new CustomException(ErrorCode.PAYMENT_ALLOCATION_INTEGRITY_ERROR);
            }
            Map<String, Share> actual = shares(candidateAllocations);
            if (Collections.disjoint(expected.keySet(), actual.keySet())) {
                continue;
            }
            if (!expected.equals(actual) || reusable != null) {
                throw new CustomException(ErrorCode.PAYMENT_NOT_CONFIRMABLE);
            }
            support.validateAndGetInitialIds(candidate, candidateAllocations, ReservationStatus.HELD);
            reusable = candidate;
        }
        if (reusable != null) {
            return response(reusable);
        }
        if (status == PaymentProcessStatus.READY) {
            throw new CustomException(ErrorCode.PAYMENT_NOT_CONFIRMABLE);
        }
        List<Registration> initialTargets = originalAllocations.stream()
                .map(PaymentAllocation::getRegistration).filter(r -> initialIds.contains(r.getId())).toList();
        if (!initialTargets.isEmpty()) {
            capacityService.prepareForRepayment(event, initialTargets, now);
        }
        List<PaymentAllocationTarget> targets = originalAllocations.stream()
                .sorted(Comparator.comparing(a -> a.getRegistration().getId()))
                .map(a -> new PaymentAllocationTarget(a.getRegistration(), a.getAllocatedAmount(), a.effectivePurpose()))
                .toList();
        BigDecimal amount = targets.stream().map(PaymentAllocationTarget::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean additional = targets.stream().anyMatch(t -> t.allocationPurpose() == PaymentPurpose.ADDITIONAL_PAYMENT);
        String correlationId = UUID.randomUUID().toString();
        Payment created;
        if (!initialIds.isEmpty() && additional) {
            created = paymentCreator.createMixedPayment(original.getOrganization(), amount, correlationId);
        } else if (additional) {
            created = original.isOrgPayment()
                    ? paymentCreator.createAdditionalPayment(original.getOrganization(), amount, correlationId)
                    : paymentCreator.createAdditionalPayment(original.getRegistration(), amount, correlationId);
        } else {
            created = original.isOrgPayment()
                    ? paymentCreator.createInitialPayment(original.getOrganization(), amount, correlationId)
                    : paymentCreator.createInitialPayment(original.getRegistration(), correlationId);
        }
        allocationCreator.create(created, targets);
        entityManager.flush();
        return response(created);
    }

    /** 잠긴 범위에서 요청한 원 주문을 확인한다. */
    private Payment required(List<Payment> payments, String id) {
        return payments.stream().filter(p -> Objects.equals(p.getId(), id)).findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.PAYMENT_NOT_FOUND));
    }

    /** 금액의 소수 자릿수 차이를 제거하고 참가자별 목적·금액을 비교한다. */
    private Map<String, Share> shares(List<PaymentAllocation> allocations) {
        Map<String, Share> result = new TreeMap<>();
        for (PaymentAllocation allocation : allocations) {
            if (allocation.getRegistration() == null || allocation.getAllocatedAmount() == null
                    || allocation.getAllocatedAmount().signum() < 0
                    || result.putIfAbsent(allocation.getRegistration().getId(), new Share(
                            allocation.effectivePurpose(), allocation.getAllocatedAmount().stripTrailingZeros())) != null) {
                throw new CustomException(ErrorCode.PAYMENT_ALLOCATION_INTEGRITY_ERROR);
            }
        }
        return result;
    }

    /** 기존 수정 응답과 동일한 주문 정보만 반환한다. */
    private Order response(Payment payment) {
        return new Order(payment.getId(), payment.getOrderId(), payment.getOrderName(), payment.getAmount());
    }

    /** 재사용 주문을 비교할 참가자별 불변 목적과 금액이다. */
    private record Share(PaymentPurpose purpose, BigDecimal amount) { }
}
