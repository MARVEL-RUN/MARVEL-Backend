package kr.co.teambrain.marvelrun.user.payment.command.application;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationAccessVerifier;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.AdditionalPaymentTargetResolver;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentAllocationCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.PaymentAllocation;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentAllocationCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.AdditionalPaymentPrepareResponse;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.AdditionalPaymentScope;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentAllocationTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 개인·단체의 추가 주문 준비를 하나의 트랜잭션으로 처리한다.
 * 현재 권한과 금융 상태를 확인하고 동일 유효 주문은 재사용한다.
 * Toss 호출·가격 재계산·정원 변경은 수행하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AdditionalPaymentPreparationService {
    private final AdditionalPaymentPreparationLoader loader;
    private final PaymentFinancialConflictGuard conflictGuard;
    private final AdditionalPaymentTargetResolver targetResolver;
    private final PaymentCreator paymentCreator;
    private final PaymentAllocationCreator allocationCreator;
    private final PaymentAllocationCommandRepository allocationRepository;
    private final PaymentCommandRepository paymentRepository;
    private final ServerTimeProvider timeProvider;

    /** 잠금 후 현재 개인 본인확인 정보로 재인증하고 추가 주문을 준비한다. */
    @Transactional
    public AdditionalPaymentPrepareResponse preparePersonal(
            String eventId, String registrationId, RegistrationAccessRequest access) {
        AdditionalPaymentScope scope = loader.lockPersonal(eventId, registrationId);
        RegistrationAccessVerifier.verifyPersonal(scope.registrations().get(0), access);
        return prepare(scope);
    }

    /** 단체장 권한으로 기존 확정 구성원의 추가 납부액만 주문화한다. */
    @Transactional
    public AdditionalPaymentPrepareResponse prepareOrganization(
            String eventId, String organizationId, OrganizationAccessRequest access) {
        AdditionalPaymentScope scope = loader.lockOrganization(eventId, organizationId);
        RegistrationAccessVerifier.verifyOrganization(scope.organization(), access);
        return prepare(scope);
    }

    /** 잠긴 최신 범위에서 충돌 검사·유효 주문 선택·교체·생성을 수행한다. */
    private AdditionalPaymentPrepareResponse prepare(AdditionalPaymentScope scope) {
        LocalDateTime now = timeProvider.currentDateTime();
        if (scope.event().getPaymentDeadline() == null) {
            throw invalid();
        }
        if (!now.isBefore(scope.event().getPaymentDeadline())) {
            throw new CustomException(ErrorCode.ADDITIONAL_PAYMENT_DEADLINE_PASSED);
        }
        conflictGuard.validate(scope.payments(), scope.cancellations(), null, null);
        List<Registration> consumed = consumedRegistrations(scope);
        List<PaymentAllocationTarget> targets = targetResolver.resolve(consumed);
        Map<String, BigDecimal> desired = new HashMap<>();
        for (PaymentAllocationTarget target : targets) {
            desired.put(target.registration().getId(), target.amount());
        }
        BigDecimal total = targets.stream().map(PaymentAllocationTarget::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Payment> additionalReady = new ArrayList<>();
        Payment reusable = null;
        List<Payment> ordered = new ArrayList<>(scope.payments());
        ordered.sort(Comparator.comparing(Payment::getId));
        for (Payment payment : ordered) {
            if (payment.getProcessStatus() != PaymentProcessStatus.READY) {
                continue;
            }
            Map<String, BigDecimal> recorded = recordedAmounts(payment);
            if (payment.getPurpose() == PaymentPurpose.REGISTRATION_TRY) {
                // 최초 주문이 추가 결제 대상까지 포함하면 이중 청구 가능성이 있어 차단한다.
                if (recorded.keySet().stream().anyMatch(desired::containsKey)) {
                    throw new CustomException(ErrorCode.PAYMENT_ADJUSTMENT_CONFLICT);
                }
                continue;
            }
            if (payment.getPurpose() != PaymentPurpose.ADDITIONAL_PAYMENT) {
                throw invalid();
            }
            additionalReady.add(payment);
            if (reusable == null && !targets.isEmpty() && matchesTarget(payment, scope)
                    && payment.getAmount().compareTo(total) == 0
                    && sameAmounts(recorded, desired)) {
                reusable = payment;
            }
        }
        for (Payment payment : additionalReady) {
            if (payment != reusable) {
                payment.invalidateForRegistrationModification();
            }
        }
        if (!additionalReady.isEmpty()) {
            paymentRepository.flush();
        }
        if (targets.isEmpty()) {
            return AdditionalPaymentPrepareResponse.none();
        }
        if (reusable != null) {
            return AdditionalPaymentPrepareResponse.from(reusable, true);
        }
        String correlationId = UUID.randomUUID().toString();
        Payment created = scope.organization() == null
                ? paymentCreator.createAdditionalPayment(targets.get(0).registration(), total, correlationId)
                : paymentCreator.createAdditionalPayment(scope.organization(), total, correlationId);
        allocationCreator.create(created, targets);
        // 응답 전에 제약 오류를 드러내고 실패 시 READY 무효화까지 함께 롤백한다.
        paymentRepository.flush();
        return AdditionalPaymentPrepareResponse.from(created, false);
    }

    /**
     * 기존 확정 예약만 추가 결제 대상으로 삼는다.
     * 단체의 최초 미결제 구성원은 기존 최초 주문 경로에 남긴다.
     */
    private List<Registration> consumedRegistrations(AdditionalPaymentScope scope) {
        Map<String, Reservation> reservations = new HashMap<>();
        for (Reservation reservation : scope.reservations()) {
            if (reservation.getRegistration() == null
                    || reservations.put(reservation.getRegistration().getId(), reservation) != null) {
                throw invalid();
            }
        }
        List<Registration> result = new ArrayList<>();
        for (Registration registration : scope.registrations()) {
            Reservation reservation = reservations.get(registration.getId());
            if (reservation == null) {
                throw new CustomException(ErrorCode.RESERVATION_NOT_FOUND);
            }
            if (registration.getPaidAmount() == null || registration.getPaidAmount().signum() < 0
                    || registration.getContractAmount() == null || registration.getContractAmount().signum() < 0) {
                throw invalid();
            }
            if (registration.getStatus() == RegistrationStatus.CANCELLATION_PENDING
                    || registration.getStatus() == RegistrationStatus.CANCELED) {
                throw new CustomException(ErrorCode.PAYMENT_ADJUSTMENT_CONFLICT);
            }
            if (reservation.getStatus() == ReservationStatus.CONSUMED) {
                registration.reconcileModificationFinancialState(ReservationStatus.CONSUMED);
                result.add(registration);
            } else if ((reservation.getStatus() == ReservationStatus.HELD
                    || reservation.getStatus() == ReservationStatus.RELEASED)
                    && registration.getPaidAmount().signum() == 0
                    && registration.getStatus() == RegistrationStatus.PAYMENT_PENDING) {
                // 최초 미결제 신청의 주문 준비는 기존 최초 결제 경로에서 처리한다.
            } else {
                throw new CustomException(ErrorCode.RESERVATION_STATE_CONFLICT);
            }
        }
        return result;
    }

    /** READY 주문의 원 귀속을 검증하며 불완전한 금융 기록을 자동 보정하지 않는다. */
    private Map<String, BigDecimal> recordedAmounts(Payment payment) {
        List<PaymentAllocation> allocations =
                allocationRepository.findAllByPayment_IdOrderByRegistration_IdAsc(payment.getId());
        if (allocations.isEmpty() || payment.getAmount() == null || payment.getAmount().signum() <= 0
                || (payment.getRegistration() == null) == (payment.getOrganization() == null)) {
            throw allocationError();
        }
        Map<String, BigDecimal> result = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (PaymentAllocation allocation : allocations) {
            Registration registration = allocation.getRegistration();
            if (registration == null || registration.getId() == null
                    || allocation.getPayment() == null
                    || !Objects.equals(payment.getId(), allocation.getPayment().getId())
                    || allocation.getAllocatedAmount() == null || allocation.getAllocatedAmount().signum() < 0
                    || result.put(registration.getId(), allocation.getAllocatedAmount()) != null) {
                throw allocationError();
            }
            if (payment.getRegistration() != null
                    && !payment.getRegistration().getId().equals(registration.getId())) {
                throw allocationError();
            }
            if (payment.getOrganization() != null
                    && (registration.getOrganization() == null
                    || !payment.getOrganization().getId().equals(registration.getOrganization().getId()))) {
                throw allocationError();
            }
            total = total.add(allocation.getAllocatedAmount());
        }
        if (total.compareTo(payment.getAmount()) != 0) {
            throw allocationError();
        }
        return result;
    }

    /** 재사용 주문의 직접 대상이 현재 요청 범위와 같은지 확인한다. */
    private boolean matchesTarget(Payment payment, AdditionalPaymentScope scope) {
        return scope.organization() == null
                ? payment.getRegistration() != null
                && payment.getRegistration().getId().equals(scope.registrations().get(0).getId())
                : payment.getOrganization() != null
                && payment.getOrganization().getId().equals(scope.organization().getId());
    }

    /** 소수점 표현 차이를 무시하고 참가자별 귀속 금액을 비교한다. */
    private boolean sameAmounts(Map<String, BigDecimal> first, Map<String, BigDecimal> second) {
        return first.keySet().equals(second.keySet())
                && first.entrySet().stream().allMatch(entry ->
                entry.getValue().compareTo(second.get(entry.getKey())) == 0);
    }

    /** 신청 금융 상태의 모순을 알린다. */
    private CustomException invalid() {
        return new CustomException(ErrorCode.REGISTRATION_FINANCIAL_STATE_INVALID);
    }

    /** 주문과 귀속 원장의 불일치를 알린다. */
    private CustomException allocationError() {
        return new CustomException(ErrorCode.PAYMENT_ALLOCATION_INTEGRITY_ERROR);
    }
}