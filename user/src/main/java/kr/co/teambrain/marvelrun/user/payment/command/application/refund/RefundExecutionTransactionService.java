package kr.co.teambrain.marvelrun.user.payment.command.application.refund;

import jakarta.persistence.EntityManager;
import kr.co.teambrain.marvelrun.user.payment.command.application.PaymentResultLogMetadata;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.*;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.TossPaymentStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_log.PaymentProcessSource;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_log.PaymentProcessType;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.PaymentCancel;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult.Refund;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.*;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.*;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.refund.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 외부 요청 시작과 결과 반영을 각각 독립된 트랜잭션으로 처리한다. HTTP는 호출하지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class RefundExecutionTransactionService {
    private final RefundExecutionLock locks;
    private final EntityManager entityManager;
    private final PaymentCancelCommandRepository cancellations;
    private final PaymentCancelAllocationCommandRepository allocations;
    private final ReservationCommandRepository reservations;
    private final PaymentProcessLogCommandRepository logs;
    private final ServerTimeProvider time;

    /** 시작 시각을 커밋한 호출자 한 명에게만 불변 실행 정보를 반환한다. */
    public Optional<RefundExecutionTicket> begin(String eventId, String organizationId, Refund refund) {
        PaymentCancel cancel = locks.lock(eventId, organizationId, refund.paymentId(), refund.paymentCancelId());
        if (cancel.getStatus() == null) { throw invalid(); }
        if (cancel.getStatus() != PaymentCancelStatus.PROCESSING || cancel.getRequestedAt() != null) {
            return Optional.empty();
        }
        Payment payment = cancel.getPayment();
        if (payment.getProcessStatus() != PaymentProcessStatus.COMPLETED
                || refund.amount() == null || refund.amount().compareTo(cancel.getCancelAmount()) != 0
                || refund.correlationId() == null || refund.correlationId().isBlank()
                || refund.correlationId().length() > 64) { throw invalid(); }

        List<PaymentCancel> history = cancellations.findAllByPaymentIdsForUpdate(List.of(payment.getId()));
        Map<String, BigDecimal> completed = new HashMap<>();
        List<PaymentRefundBudget.CancelAmount> otherAmounts = new ArrayList<>();
        for (PaymentCancel previous : history) {
            // 현재 읽기와 1차 캐시의 값이 다를 가능성을 제거한다. 아직 엔티티를 변경하지 않았다.
            entityManager.refresh(previous, LockModeType.PESSIMISTIC_WRITE);
            if (previous.getId().equals(cancel.getId())) { continue; }
            if (previous.getStatus() == null) { throw invalid(); }
            otherAmounts.add(new PaymentRefundBudget.CancelAmount(previous.getStatus(), previous.getCancelAmount()));
            if (previous.getStatus() == PaymentCancelStatus.DONE) {
                if (previous.getTransactionKey() == null
                        || completed.put(previous.getTransactionKey(), previous.getCancelAmount()) != null) { throw invalid(); }
            }
        }
        PaymentRefundBudget.calculate(payment.getAmount(), otherAmounts).validateRequest(cancel.getCancelAmount());
        List<RefundExecutionTicket.Share> shares = loadShares(cancel);
        validateAllocationBudgets(cancel, shares);
        List<Registration> targets = lockAndValidateRegistrations(eventId, organizationId, payment, shares);
        lockReservations(targets);
        TossCancelAttempt attempt = new TossCancelAttempt(cancel.getId(), payment.getPaymentKey(),
                payment.getOrderId(), cancel.getIdempotencyKey(), cancel.getCancelReason(),
                payment.getAmount(), cancel.getCancelAmount(), completed);
        if (!cancel.startRefund(time.currentDateTime())) { return Optional.empty(); }
        RefundExecutionTicket ticket = new RefundExecutionTicket(eventId, organizationId,
                payment.getId(), refund.correlationId(), attempt, shares);
        appendLog(cancel, ticket.correlationId(), PaymentProcessType.CANCEL_REQUESTED, null);
        entityManager.flush();
        return Optional.of(ticket);
    }

    /** 취소 성공과 참가자 순납부액·상태·로그를 하나의 트랜잭션으로 반영한다. */
    public void apply(RefundExecutionTicket ticket, TossCancelOutcome outcome) {
        PaymentCancel cancel = locks.lock(ticket.eventId(), ticket.organizationId(), ticket.paymentId(),
                ticket.attempt().paymentCancelId());
        validateTicket(cancel, ticket);
        if (outcome == null || outcome.kind() == null) { throw invalid(); }
        if (outcome.kind() != TossCancelOutcome.Kind.VERIFIED) {
            PaymentCancelStatus next = outcome.kind() == TossCancelOutcome.Kind.REJECTED
                    ? PaymentCancelStatus.FAILED : PaymentCancelStatus.UNKNOWN;
            if (cancel.recordRefundProblem(next, outcome.errorCode(), outcome.message())) {
                appendLog(cancel, ticket.correlationId(), next == PaymentCancelStatus.FAILED
                        ? PaymentProcessType.CANCEL_FAILED : PaymentProcessType.CANCEL_UNKNOWN, outcome);
            }
            entityManager.flush();
            return;
        }
        VerifiedTossCancellation verified = outcome.cancellation();
        if (verified == null) { throw invalid(); }
        String expected = verified.refundableAmount() != null && verified.refundableAmount().signum() == 0
                ? "CANCELED" : "PARTIAL_CANCELED";
        BigDecimal expectedBalance = ticket.attempt().originalAmount().subtract(ticket.attempt().cancelAmount());
        for (BigDecimal amount : ticket.attempt().completedCancels().values()) {
            expectedBalance = expectedBalance.subtract(amount);
        }
        if (!expected.equals(verified.paymentStatus()) || verified.refundableAmount() == null
                || expectedBalance.compareTo(verified.refundableAmount()) != 0 || verified.canceledAt() == null) { throw invalid(); }
        // 동일 결과의 재반영은 신청 순납부액을 다시 차감하지 않는다.
        boolean changed = cancel.completeRefund(verified.transactionKey(), verified.cancelAmount(),
                verified.refundableAmount(), verified.canceledAt().atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDateTime());
        if (!changed) { return; }
        List<RefundExecutionTicket.Share> currentShares = loadShares(cancel);
        if (!currentShares.equals(ticket.shares())) { throw invalid(); }
        List<Registration> targets = lockAndValidateRegistrations(ticket.eventId(), ticket.organizationId(),
                cancel.getPayment(), currentShares);
        Map<String, BigDecimal> amounts = amountsByRegistration(currentShares);
        Map<String, Reservation> byRegistration = lockReservations(targets);
        for (Registration registration : targets) {
            Reservation reservation = byRegistration.get(registration.getId());
            if (reservation == null) { throw invalid(); }
            registration.applySuccessfulRefund(amounts.get(registration.getId()));
            registration.reconcileModificationFinancialState(reservation.getStatus());
        }
        cancel.getPayment().recordVerifiedRefundStatus(TossPaymentStatus.valueOf(expected));
        appendLog(cancel, ticket.correlationId(), PaymentProcessType.CANCEL_SUCCEEDED, outcome);
        entityManager.flush();
    }

    /** 전송 당시 귀속이 원결제의 남은 참가자별 한도 안에 있는지 재확인한다. */
    private void validateAllocationBudgets(PaymentCancel current, List<RefundExecutionTicket.Share> shares) {
        for (RefundExecutionTicket.Share share : shares) {
            PaymentAllocation original = entityManager.find(PaymentAllocation.class, share.originalAllocationId());
            List<PaymentRefundBudget.CancelAmount> previous = new ArrayList<>();
            for (PaymentCancelAllocation allocation : allocations.findAllByOriginalAllocationId(original.getId())) {
                if (!allocation.getPaymentCancel().getId().equals(current.getId())) {
                    previous.add(new PaymentRefundBudget.CancelAmount(
                            allocation.getPaymentCancel().getStatus(), allocation.getAllocatedAmount()));
                }
            }
            PaymentRefundBudget.calculate(original.getAllocatedAmount(), previous).validateRequest(share.amount());
        }
    }

    /** 부모와 귀속의 관계·합계·중복을 검사하고 고정 순서의 실행 스냅샷을 만든다. */
    private List<RefundExecutionTicket.Share> loadShares(PaymentCancel cancel) {
        List<RefundExecutionTicket.Share> shares = new ArrayList<>();
        Set<String> originals = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (PaymentCancelAllocation allocation : allocations.findAllByCancelId(cancel.getId())) {
            PaymentAllocation original = allocation.getOriginalAllocation();
            if (allocation.getId() == null || original == null || original.getId() == null
                    || !cancel.getPayment().getId().equals(original.getPayment().getId())
                    || !cancel.getId().equals(allocation.getPaymentCancel().getId())
                    || original.getRegistration() == null || !originals.add(original.getId())
                    || allocation.getAllocatedAmount() == null || allocation.getAllocatedAmount().signum() <= 0) { throw invalid(); }
            total = total.add(allocation.getAllocatedAmount());
            shares.add(new RefundExecutionTicket.Share(allocation.getId(), original.getId(),
                    original.getRegistration().getId(), allocation.getAllocatedAmount()));
        }
        if (shares.isEmpty() || total.compareTo(cancel.getCancelAmount()) != 0) { throw invalid(); }
        shares.sort(Comparator.comparing(RefundExecutionTicket.Share::cancelAllocationId));
        return List.copyOf(shares);
    }

    /** 금융 관련 필드는 최신 잠금 값으로 확인하고 개인정보를 과거 스냅샷으로 덮어쓰지 않는다. */
    private List<Registration> lockAndValidateRegistrations(String eventId, String organizationId,
                                                            Payment payment, List<RefundExecutionTicket.Share> shares) {
        Map<String, BigDecimal> amounts = amountsByRegistration(shares);
        List<Registration> targets = new ArrayList<>();
        for (String id : new TreeSet<>(amounts.keySet())) {
            Registration registration = entityManager.find(Registration.class, id);
            if (registration == null) { throw invalid(); }
            entityManager.refresh(registration, LockModeType.PESSIMISTIC_WRITE);
            String actualOrganization = registration.getOrganization() == null ? null : registration.getOrganization().getId();
            if (!eventId.equals(registration.getEvent().getId()) || !Objects.equals(organizationId, actualOrganization)
                    || registration.getPaidAmount() == null || registration.getContractAmount() == null
                    || registration.getPaidAmount().subtract(registration.getContractAmount()).compareTo(amounts.get(id)) < 0
                    || (payment.getRegistration() == null) == (payment.getOrganization() == null)
                    || (payment.getRegistration() != null && !id.equals(payment.getRegistration().getId()))
                    || (payment.getOrganization() != null && !payment.getOrganization().getId().equals(actualOrganization))) { throw invalid(); }
            targets.add(registration);
        }
        return targets;
    }

    /** 신청 전체 잠금 이후 예약을 ID 순으로 보호하고 환불 가능한 확보 상태인지 확인한다. */
    private Map<String, Reservation> lockReservations(List<Registration> targets) {
        List<String> ids = targets.stream().map(Registration::getId).toList();
        List<Reservation> rows = new ArrayList<>(reservations.findAllByRegistrationIds(ids));
        rows.sort(Comparator.comparing(Reservation::getId));
        Map<String, Reservation> result = new HashMap<>();
        for (Reservation reservation : rows) {
            entityManager.refresh(reservation, LockModeType.PESSIMISTIC_WRITE);
            if (result.put(reservation.getRegistration().getId(), reservation) != null) { throw invalid(); }
        }
        for (Registration registration : targets) {
            Reservation reservation = result.get(registration.getId());
            if (reservation == null) { throw invalid(); }
            if (registration.isSoftDeleted()) {
                if (registration.getContractAmount().signum() != 0
                        || reservation.getStatus() != kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus.RELEASED
                        || registration.getStatus() != kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus.CANCELLATION_PENDING) { throw invalid(); }
            } else if (reservation.getStatus() != kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus.CONSUMED
                    || registration.getStatus() != kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus.PARTIAL_REFUND_REQUIRED) { throw invalid(); }
        }
        return result;
    }

    /** 여러 원귀속이 같은 신청에 연결되더라도 반영할 금액은 신청별로 합산한다. */
    private static Map<String, BigDecimal> amountsByRegistration(List<RefundExecutionTicket.Share> shares) {
        Map<String, BigDecimal> amounts = new TreeMap<>();
        for (RefundExecutionTicket.Share share : shares) {
            amounts.merge(share.registrationId(), share.amount(), BigDecimal::add);
        }
        return amounts;
    }

    /** 시작 이후 식별값이나 금액이 바뀐 시도를 다른 요청 결과로 완료하지 못하게 한다. */
    private static void validateTicket(PaymentCancel cancel, RefundExecutionTicket ticket) {
        TossCancelAttempt attempt = ticket.attempt();
        Payment payment = cancel.getPayment();
        if (!attempt.paymentCancelId().equals(cancel.getId())
                || !attempt.paymentKey().equals(payment.getPaymentKey())
                || !attempt.orderId().equals(payment.getOrderId())
                || !attempt.idempotencyKey().equals(cancel.getIdempotencyKey())
                || attempt.cancelAmount().compareTo(cancel.getCancelAmount()) != 0
                || attempt.originalAmount().compareTo(payment.getAmount()) != 0) { throw invalid(); }
    }

    /** 개인정보와 Toss 원문 대신 식별자·상태·금액·거래키 중심의 추적 로그를 남긴다. */
    private void appendLog(PaymentCancel cancel, String correlationId, PaymentProcessType type, TossCancelOutcome outcome) {
        VerifiedTossCancellation evidence = outcome == null ? null : outcome.cancellation();
        logs.save(PaymentProcessLog.builder().paymentId(cancel.getPayment().getId()).paymentCancelId(cancel.getId())
                .orderId(cancel.getPayment().getOrderId()).correlationId(correlationId)
                .processType(type).source(PaymentProcessSource.API)
                .transactionKey(evidence == null ? cancel.getTransactionKey() : evidence.transactionKey())
                .httpStatus(outcome == null ? null : outcome.httpStatus())
                .errorCode(cancel.getErrorCode()).errorMessage(cancel.getErrorMessage())
                .metadata(PaymentResultLogMetadata.refund(Map.of("amount", cancel.getCancelAmount(), "status", cancel.getStatus().name()), cancel.getStatus(), outcome))
                .build());
    }

    /** 손상된 금융 연결 정보를 임의로 보정하지 않는다. */
    private static CustomException invalid() {
        return new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
    }
}