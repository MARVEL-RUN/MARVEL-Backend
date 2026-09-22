package kr.co.teambrain.marvelrun.admin.event.command.application.service;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.admin.capacity.command.repository.CapacityCommandRepository;
import kr.co.teambrain.marvelrun.admin.capacity.command.repository.ReservationItemCommandRepository;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.RegistrationDeleteResponse;
import kr.co.teambrain.marvelrun.admin.event.command.repository.SouvenirCommandRepository;
import kr.co.teambrain.marvelrun.admin.payment.command.AdminRefundLockRepository;
import kr.co.teambrain.marvelrun.admin.payment.command.AdminRefundLockedScope;
import kr.co.teambrain.marvelrun.admin.payment.command.AdminRefundPreparationStore;
import kr.co.teambrain.marvelrun.admin.payment.command.AdminRefundTime;
import kr.co.teambrain.marvelrun.admin.payment.command.application.dto.RefundPaymentLedger;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.common.json_object.ReservationHistoryEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 미결제 신청 취소를 승인·환불과 동일한 잠금 순서로 처리한다.
 * 대회 → 단체 → 결제 → 취소 → 신청 → 귀속 → 예약 → 정원 ID 순서를 사용한다.
 * 외부 PG를 호출하거나 금융 원장을 삭제하지 않으며 실패는 전체 롤백한다.
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
        // 최초 조회는 잠금 범위 확인용이다. 판단에는 이후 현재 읽기 결과만 사용한다.
        List<Object[]> observed = entityManager.createQuery("""
                select r.event.id, o.id from Registration r
                left join r.organization o where r.id=:id
                """, Object[].class).setParameter("id", registrationId).getResultList();
        if (observed.size() != 1) { throw error(ErrorCode.REGISTRATION_NOT_FOUND); }
        String eventId = (String) observed.getFirst()[0];
        String organizationId = (String) observed.getFirst()[1];
        if (!locks.lockEvent(eventId)) { throw error(ErrorCode.EVENT_NOT_FOUND); }
        if (organizationId != null && !locks.lockOrganization(eventId, organizationId)) {
            throw error(ErrorCode.ORGANIZATION_NOT_FOUND);
        }
        var payments = locks.lockPayments(eventId, organizationId, registrationId);
        requireBound(payments.size());
        for (var payment : payments) {
            if (payment.status() == null) { throw integrity(); }
            switch (payment.status()) {
                case READY, FAILED, INVALIDATED, COMPLETED -> { }
                default -> throw error(ErrorCode.REGISTRATION_MODIFICATION_PAYMENT_CONFLICT);
            }
        }
        var cancellations = locks.lockCancellations(payments.stream().map(p -> p.id()).sorted().toList());
        requireBound(cancellations.size());
        for (var cancellation : cancellations) {
            if (cancellation.status() == null) { throw integrity(); }
            if (cancellation.status() == PaymentCancelStatus.PROCESSING
                    || cancellation.status() == PaymentCancelStatus.UNKNOWN) {
                throw error(ErrorCode.PAYMENT_CANCEL_CONFLICT);
            }
        }
        var registrations = locks.lockRegistrations(eventId, List.of(registrationId));
        if (registrations.size() != 1) { throw error(ErrorCode.REGISTRATION_NOT_FOUND); }
        if (!Objects.equals(organizationId, registrations.getFirst().organizationId())) {
            throw error(ErrorCode.CONCURRENT_MODIFICATION);
        }
        Registration registration = store.current(Registration.class, registrationId);
        if (!Objects.equals(eventId, registration.getEvent().getId())
                || !Objects.equals(organizationId, registration.getOrganization() == null
                    ? null : registration.getOrganization().getId())) { throw integrity(); }
        if (registration.getPaidAmount() == null || registration.getPaidAmount().signum() != 0
                || registration.getContractAmount() == null || registration.getContractAmount().signum() < 0) {
            throw error(ErrorCode.REGISTRATION_FINANCIAL_STATE_INVALID);
        }
        boolean repeated = registration.isSoftDeleted()
                && registration.getStatus() == RegistrationStatus.EXPIRED
                && registration.getContractAmount().signum() == 0;
        if (!repeated && (registration.isSoftDeleted()
                || registration.getStatus() != RegistrationStatus.PAYMENT_PENDING)) {
            throw error(ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET);
        }
        var scope = new AdminRefundLockedScope(eventId, organizationId, registrations, payments, cancellations);
        List<RefundPaymentLedger> ledgers = store.ledgers(scope);
        for (RefundPaymentLedger ledger : ledgers) {
            // Allocation이 없는 주문도 삭제하거나 추측해 통과시키지 않는다.
            if (ledger.allocations().isEmpty()) { throw integrity(); }
            boolean belongsToTarget = ledger.payment().getRegistration() != null
                    && registrationId.equals(ledger.payment().getRegistration().getId());
            belongsToTarget |= ledger.allocations().stream()
                    .anyMatch(a -> registrationId.equals(a.getRegistration().getId()));
            if (belongsToTarget && ledger.payment().getProcessStatus() == PaymentProcessStatus.COMPLETED) {
                throw error(ErrorCode.REGISTRATION_MODIFICATION_PAYMENT_CONFLICT);
            }
        }
        List<Reservation> reservations = store.reservations(List.of(registrationId));
        if (reservations.size() != 1) { throw error(ErrorCode.RESERVATION_NOT_FOUND); }
        Reservation reservation = reservations.getFirst();
        if (repeated) {
            if (reservation.getStatus() != ReservationStatus.RELEASED) { throw integrity(); }
            return new RegistrationDeleteResponse("이미 취소된 미결제 신청입니다. 정원은 다시 반환하지 않았습니다.");
        }
        if (reservation.getStatus() != ReservationStatus.HELD
                && reservation.getStatus() != ReservationStatus.RELEASED) {
            throw error(ErrorCode.RESERVATION_STATE_CONFLICT);
        }
        Map<String, Integer> quantities = new TreeMap<>();
        if (reservation.getStatus() == ReservationStatus.HELD) {
            var allocations = items.findAllocations(List.of(reservation.getId()));
            if (allocations.isEmpty()) { throw error(ErrorCode.CAPACITY_COUNTER_MISMATCH); }
            for (var allocation : allocations) {
                if (allocation.quantity() <= 0 || allocation.capacityId() == null) {
                    throw error(ErrorCode.CAPACITY_COUNTER_MISMATCH);
                }
                quantities.merge(allocation.capacityId(), allocation.quantity(), Math::addExact);
            }
        }
        String message = responseMessage(registration);
        LocalDateTime now = time.now();
        for (RefundPaymentLedger ledger : ledgers) {
            ledger.payment().invalidateForRegistrationModification();
        }
        if (reservation.releaseHeld()) {
            reservation.appendHistory(ReservationHistoryEntry.Action.RELEASE, now, null,
                    "관리자에 의한 미결제 신청 취소", List.of());
        }
        registration.expireByAdmin(now);
        // 상태/version 검증을 먼저 확정하고 영속성 컨텍스트를 clear하지 않는다.
        store.flush();
        for (var entry : quantities.entrySet()) {
            if (capacities.releaseHeld(eventId, entry.getKey(), entry.getValue(), now) != 1) {
                throw error(ErrorCode.CAPACITY_COUNTER_MISMATCH);
            }
        }
        return new RegistrationDeleteResponse(message);
    }

    /** 기존 프론트가 사용하는 message 응답과 코스·기념품 안내를 유지한다. */
    private String responseMessage(Registration registration) {
        List<String> details = new java.util.ArrayList<>();
        if (registration.getSouvenirJson() != null) {
            for (var choice : registration.getSouvenirJson()) {
                souvenirs.findById(choice.souvenirId()).ifPresent(souvenir -> {
                    String size = choice.selectedSize() == null || choice.selectedSize().isBlank()
                            ? "" : "(" + choice.selectedSize() + ")";
                    details.add(souvenir.getName() + size);
                });
            }
        }
        return String.format("삭제 완료: [%s] 코스 및 [%s] 정원이 확보되었습니다.",
                registration.getEventCategory().getName(),
                details.isEmpty() ? "선택된 기념품 없음" : String.join(", ", details));
    }

    /** 원장을 일부만 검증하는 대신 기존 관리자 환불과 같은 상한에서 거절한다. */
    private static void requireBound(int count) {
        if (count > AdminRefundLockRepository.MAX_LEDGER_ROWS) { throw integrity(); }
    }

    /** 금융 귀속 누락은 자동 보정하지 않는다. */
    private static CustomException integrity() { return error(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR); }

    /** 업무 예외는 트랜잭션 바깥으로 전달하여 모든 변경을 롤백한다. */
    private static CustomException error(ErrorCode code) { return new CustomException(code); }
}
