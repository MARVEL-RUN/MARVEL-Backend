package kr.co.teambrain.marvelrun.admin.event.command.application.service;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.dto.ReservationAllocation;
import kr.co.teambrain.marvelrun.admin.capacity.command.repository.CapacityCommandRepository;
import kr.co.teambrain.marvelrun.admin.capacity.command.repository.ReservationItemCommandRepository;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.RegistrationDeleteResponse;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.UnpaidRegistrationBatchResponse.Failure;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.UnpaidRegistrationBatchResponse.Success;
import kr.co.teambrain.marvelrun.admin.event.command.repository.SouvenirCommandRepository;
import kr.co.teambrain.marvelrun.admin.payment.command.AdminRefundLockRepository;
import kr.co.teambrain.marvelrun.admin.payment.command.AdminRefundLockedScope;
import kr.co.teambrain.marvelrun.admin.payment.command.AdminRefundLockedScope.CancelRow;
import kr.co.teambrain.marvelrun.admin.payment.command.AdminRefundLockedScope.PaymentRow;
import kr.co.teambrain.marvelrun.admin.payment.command.AdminRefundLockedScope.RegistrationRow;
import kr.co.teambrain.marvelrun.admin.payment.command.AdminRefundPreparationStore;
import kr.co.teambrain.marvelrun.admin.payment.command.AdminRefundTime;
import kr.co.teambrain.marvelrun.admin.payment.command.application.dto.RefundPaymentLedger;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.common.json_object.ReservationHistoryEntry;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/**
 * 관리자 미결제 신청 취소를 승인·환불과 동일한 잠금 순서로 처리한다.
 * 대회 → 단체 → 결제 → 취소 → 신청 → 귀속 → 예약 → 정원 ID 순서를 사용한다.
 * 외부 PG를 호출하거나 금융 원장을 삭제하지 않으며 실패한 신청의 변경은 전체 롤백한다.
 * 일괄 요청의 단건 처리와 실패 정보 조회는 각각 독립된 트랜잭션으로 수행한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AdminUnpaidRegistrationCancellationService {
    private final EntityManager entityManager;
    private final AdminRefundLockRepository locks;
    private final AdminRefundPreparationStore store;
    private final CapacityCommandRepository capacities;
    private final ReservationItemCommandRepository items;
    private final SouvenirCommandRepository souvenirs;
    private final AdminRefundTime time;

    /**
     * 기존 DELETE API의 EXPIRED·소프트 삭제 응답을 유지한다.
     * 같은 단체의 READY 주문은 기존 수정 정책대로 무효화하고 모든 귀속은 보존한다.
     * 과거 다른 구성원의 완료 결제는 허용하지만 대상 참가자의 완료 결제는 거절한다.
     */
    public RegistrationDeleteResponse cancel(String registrationId) {
        CancellationOutcome outcome = performUnpaidCancellation(null, registrationId);

        return new RegistrationDeleteResponse(outcome.message());
    }

    /** 대회 범위를 확인한 신청 한 건을 독립적으로 취소하고 재요청 여부를 반환한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Success cancelUnpaidRegistrationInEvent(String eventId, String registrationId) {
        if (eventId == null || eventId.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_UNPAID_CANCELLATION_REQUEST);
        }

        CancellationOutcome outcome = performUnpaidCancellation(eventId, registrationId);

        return new Success(registrationId, outcome.alreadyCanceled());
    }

    /** 취소 롤백 이후 같은 대회에 속한 신청의 표시 정보만 별도 트랜잭션에서 조회한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Failure loadCancellationFailure(
            String eventId,
            String registrationId,
            ErrorCode errorCode
    ) {
        List<Object[]> details = entityManager.createQuery("""
                select r.name, r.phNum, r.birth, category.name
                from Registration r
                left join r.eventCategory category
                where r.id = :registrationId and r.event.id = :eventId
                """, Object[].class)
                .setParameter("registrationId", registrationId)
                .setParameter("eventId", eventId)
                .getResultList();

        if (details.isEmpty()) {
            return Failure.withoutRegistrationDetails(registrationId, errorCode);
        }

        Object[] detail = details.getFirst();

        return new Failure(
                registrationId,
                (String) detail[0],
                (String) detail[1],
                (String) detail[2],
                (String) detail[3],
                errorCode,
                errorCode.getMessage()
        );
    }

    /** 단건·일괄 요청에 동일한 금융 검증·주문 무효화·신청 만료·정원 반환을 적용한다. */
    private CancellationOutcome performUnpaidCancellation(
            String expectedEventId,
            String registrationId
    ) {
        // 최초 조회는 잠금 범위 확인용이다. 판단에는 이후 현재 읽기 결과만 사용한다.
        List<Object[]> observed = entityManager.createQuery("""
                select r.event.id, o.id from Registration r
                left join r.organization o where r.id=:id
                """, Object[].class)
                .setParameter("id", registrationId)
                .getResultList();

        if (observed.size() != 1) {
            throw new CustomException(ErrorCode.REGISTRATION_NOT_FOUND);
        }

        String eventId = (String) observed.getFirst()[0];
        String organizationId = (String) observed.getFirst()[1];

        validateRequestedEvent(expectedEventId, eventId);

        if (!locks.lockEvent(eventId)) {
            throw new CustomException(ErrorCode.EVENT_NOT_FOUND);
        }

        if (organizationId != null && !locks.lockOrganization(eventId, organizationId)) {
            throw new CustomException(ErrorCode.ORGANIZATION_NOT_FOUND);
        }

        List<PaymentRow> payments = locks.lockPayments(eventId, organizationId, registrationId);
        validateLedgerRowLimit(payments.size());

        for (PaymentRow payment : payments) {
            if (payment.status() == null) {
                throw new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
            }

            switch (payment.status()) {
                case READY, FAILED, INVALIDATED, COMPLETED -> { }
                default -> throw new CustomException(ErrorCode.REGISTRATION_MODIFICATION_PAYMENT_CONFLICT);
            }
        }

        List<String> paymentIds = payments.stream()
                .map(PaymentRow::id)
                .sorted()
                .toList();
        List<CancelRow> cancellations = locks.lockCancellations(paymentIds);
        validateLedgerRowLimit(cancellations.size());

        for (CancelRow cancellation : cancellations) {
            if (cancellation.status() == null) {
                throw new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
            }

            if (cancellation.status() == PaymentCancelStatus.PROCESSING
                    || cancellation.status() == PaymentCancelStatus.UNKNOWN) {
                throw new CustomException(ErrorCode.PAYMENT_CANCEL_CONFLICT);
            }
        }

        List<RegistrationRow> registrations = locks.lockRegistrations(eventId, List.of(registrationId));

        if (registrations.size() != 1) {
            throw new CustomException(ErrorCode.REGISTRATION_NOT_FOUND);
        }

        if (!Objects.equals(organizationId, registrations.getFirst().organizationId())) {
            throw new CustomException(ErrorCode.CONCURRENT_MODIFICATION);
        }

        Registration registration = store.current(Registration.class, registrationId);
        validateRequestedEvent(expectedEventId, registration.getEvent().getId());

        if (!Objects.equals(eventId, registration.getEvent().getId())
                || !Objects.equals(organizationId, registration.getOrganization() == null
                    ? null : registration.getOrganization().getId())) {
            throw new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
        }

        if (registration.getPaidAmount() == null
                || registration.getPaidAmount().signum() != 0
                || registration.getContractAmount() == null
                || registration.getContractAmount().signum() < 0) {
            throw new CustomException(ErrorCode.REGISTRATION_FINANCIAL_STATE_INVALID);
        }

        boolean repeated = registration.isSoftDeleted()
                && registration.getStatus() == RegistrationStatus.EXPIRED
                && registration.getContractAmount().signum() == 0;

        if (!repeated && (registration.isSoftDeleted()
                || registration.getStatus() != RegistrationStatus.PAYMENT_PENDING)) {
            ErrorCode errorCode = expectedEventId == null
                    ? ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET
                    : ErrorCode.INVALID_UNPAID_CANCELLATION_TARGET;

            throw new CustomException(errorCode);
        }

        AdminRefundLockedScope scope = new AdminRefundLockedScope(
                eventId,
                organizationId,
                registrations,
                payments,
                cancellations
        );
        List<RefundPaymentLedger> ledgers = store.ledgers(scope);

        for (RefundPaymentLedger ledger : ledgers) {
            // Allocation이 없는 주문도 삭제하거나 추측해 통과시키지 않는다.
            if (ledger.allocations().isEmpty()) {
                throw new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
            }

            boolean belongsToTarget = ledger.payment().getRegistration() != null
                    && registrationId.equals(ledger.payment().getRegistration().getId());
            belongsToTarget |= ledger.allocations().stream()
                    .anyMatch(a -> registrationId.equals(a.getRegistration().getId()));

            if (belongsToTarget && ledger.payment().getProcessStatus() == PaymentProcessStatus.COMPLETED) {
                throw new CustomException(ErrorCode.REGISTRATION_MODIFICATION_PAYMENT_CONFLICT);
            }
        }

        List<Reservation> reservations = store.reservations(List.of(registrationId));

        if (reservations.size() != 1) {
            throw new CustomException(ErrorCode.RESERVATION_NOT_FOUND);
        }

        Reservation reservation = reservations.getFirst();

        if (repeated) {
            if (reservation.getStatus() != ReservationStatus.RELEASED) {
                throw new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
            }

            return new CancellationOutcome(true, "이미 취소된 미결제 신청입니다. 정원은 다시 반환하지 않았습니다.");
        }

        if (reservation.getStatus() != ReservationStatus.HELD
                && reservation.getStatus() != ReservationStatus.RELEASED) {
            throw new CustomException(ErrorCode.RESERVATION_STATE_CONFLICT);
        }

        Map<String, Integer> quantities = new TreeMap<>();

        if (reservation.getStatus() == ReservationStatus.HELD) {
            List<ReservationAllocation> allocations = items.findAllocations(List.of(reservation.getId()));

            if (allocations.isEmpty()) {
                throw new CustomException(ErrorCode.CAPACITY_COUNTER_MISMATCH);
            }

            for (ReservationAllocation allocation : allocations) {
                if (allocation.quantity() <= 0 || allocation.capacityId() == null) {
                    throw new CustomException(ErrorCode.CAPACITY_COUNTER_MISMATCH);
                }

                try {
                    quantities.merge(allocation.capacityId(), allocation.quantity(), Math::addExact);
                } catch (ArithmeticException exception) {
                    throw new CustomException(ErrorCode.CAPACITY_COUNTER_MISMATCH, exception);
                }
            }
        }

        String message = buildReleasedCapacityMessage(registration);
        LocalDateTime now = time.now();

        for (RefundPaymentLedger ledger : ledgers) {
            ledger.payment().invalidateForRegistrationModification();
        }

        if (reservation.releaseHeld()) {
            reservation.appendHistory(
                    ReservationHistoryEntry.Action.RELEASE,
                    now,
                    null,
                    "관리자에 의한 미결제 신청 취소",
                    List.of()
            );
        }

        registration.expireByAdmin(now);

        // 상태/version 검증을 먼저 확정하고 영속성 컨텍스트를 clear하지 않는다.
        store.flush();

        for (Map.Entry<String, Integer> entry : quantities.entrySet()) {
            if (capacities.releaseHeld(eventId, entry.getKey(), entry.getValue(), now) != 1) {
                throw new CustomException(ErrorCode.CAPACITY_COUNTER_MISMATCH);
            }
        }

        return new CancellationOutcome(false, message);
    }

    /** 기존 프론트가 사용하는 message 응답과 코스·기념품 안내를 유지한다. */
    private String buildReleasedCapacityMessage(Registration registration) {
        List<String> details = new ArrayList<>();

        if (registration.getSouvenirJson() != null) {
            for (SouvenirJson choice : registration.getSouvenirJson()) {
                souvenirs.findById(choice.souvenirId()).ifPresent(souvenir -> {
                    String size = choice.selectedSize() == null || choice.selectedSize().isBlank()
                            ? "" : "(" + choice.selectedSize() + ")";
                    details.add(souvenir.getName() + size);
                });
            }
        }

        return String.format(
                "삭제 완료: [%s] 코스 및 [%s] 정원이 확보되었습니다.",
                registration.getEventCategory().getName(),
                details.isEmpty() ? "선택된 기념품 없음" : String.join(", ", details)
        );
    }

    /** 원장을 일부만 검증하는 대신 기존 관리자 환불과 같은 상한에서 거절한다. */
    private void validateLedgerRowLimit(int count) {
        if (count > AdminRefundLockRepository.MAX_LEDGER_ROWS) {
            throw new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
        }
    }

    /** 일괄 요청은 조회 시점과 잠금 이후 모두 요청 대회의 신청만 허용한다. */
    private void validateRequestedEvent(String expectedEventId, String actualEventId) {
        if (expectedEventId != null && !expectedEventId.equals(actualEventId)) {
            throw new CustomException(ErrorCode.REGISTRATION_EVENT_MISMATCH);
        }
    }

    /** 단건 응답 문구와 일괄 응답의 재요청 여부를 같은 잠금 상태에서 결정한다. */
    private record CancellationOutcome(boolean alreadyCanceled, String message) {
    }
}
