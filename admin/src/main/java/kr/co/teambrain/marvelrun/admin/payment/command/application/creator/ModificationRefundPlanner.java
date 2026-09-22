package kr.co.teambrain.marvelrun.admin.payment.command.application.creator;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelType;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.PaymentCancel;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.admin.payment.command.application.domain.PaymentAllocation;
import kr.co.teambrain.marvelrun.admin.payment.command.application.domain.PaymentCancelAllocation;
import kr.co.teambrain.marvelrun.admin.payment.command.application.domain.PaymentRefundBudget;
import kr.co.teambrain.marvelrun.admin.payment.command.application.dto.PaymentCancelAllocationTarget;
import kr.co.teambrain.marvelrun.admin.payment.command.application.dto.RefundPaymentLedger;
import kr.co.teambrain.marvelrun.admin.payment.command.application.dto.RefundPreparationPlan;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.*;

/** 수정 후 초과 납부액을 원 결제·원 귀속별 잔액 안에서 배분한다. 조회·저장·외부 호출은 하지 않는다. */
@Component
public class ModificationRefundPlanner {
    /**
     * 모든 원장을 검증한 뒤에만 저장 가능한 계획을 반환한다.
     * 원 Payment ID, Allocation ID 순으로 배분하며 승인 일시 순서를 의미하지 않는다.
     */
    public List<RefundPreparationPlan> plan(List<Registration> registrations, List<RefundPaymentLedger> ledgers) {
        Map<String, BigDecimal> needs = new TreeMap<>();
        Map<String, Registration> targets = new TreeMap<>();
        for (Registration registration : registrations) {
            if (registration == null || registration.getId() == null
                    || targets.put(registration.getId(), registration) != null) { throw invalid(); }
            money(registration.getContractAmount(), false);
            money(registration.getPaidAmount(), false);
            BigDecimal excess = registration.getPaidAmount().subtract(registration.getContractAmount());
            if (excess.signum() <= 0) { continue; }
            if (registration.isSoftDeleted()) {
                if (registration.getContractAmount().signum() != 0
                        || registration.getStatus() != RegistrationStatus.CANCELLATION_PENDING) { throw invalid(); }
            } else if (registration.getStatus() != RegistrationStatus.PARTIAL_REFUND_REQUIRED) { throw invalid(); }
            needs.put(registration.getId(), excess);
        }
        if (needs.isEmpty()) { return List.of(); }
        List<RefundPaymentLedger> ordered = new ArrayList<>(ledgers);
        for (RefundPaymentLedger ledger : ordered) {
            if (ledger == null || ledger.payment() == null || ledger.payment().getId() == null) { throw invalid(); }
        }
        ordered.sort(Comparator.comparing(ledger -> ledger.payment().getId()));
        Set<String> paymentIds = new HashSet<>();
        Map<String, BigDecimal> netPaid = new HashMap<>();
        Map<String, BigDecimal> allocationRemaining = new HashMap<>();
        Map<String, PaymentRefundBudget> paymentBudgets = new HashMap<>();
        for (RefundPaymentLedger ledger : ordered) {
            Payment payment = ledger.payment();
            if (!paymentIds.add(payment.getId()) || payment.getProcessStatus() == null) { throw invalid(); }
            if (payment.getProcessStatus() == PaymentProcessStatus.CONFIRMING
                    || payment.getProcessStatus() == PaymentProcessStatus.UNKNOWN) { throw conflict(); }
            if (payment.getProcessStatus() != PaymentProcessStatus.COMPLETED) {
                if (!ledger.cancellations().isEmpty() || !ledger.cancelAllocations().isEmpty()) { throw invalid(); }
                continue;
            }
            validateLedger(ledger, allocationRemaining, netPaid, paymentBudgets);
        }
        // 현재 순납부액과 원장의 순납부 합계를 대조하여 누락 원장으로 임의 환불하지 않는다.
        for (String registrationId : needs.keySet()) {
            if (netPaid.getOrDefault(registrationId, BigDecimal.ZERO)
                    .compareTo(targets.get(registrationId).getPaidAmount()) != 0) { throw invalid(); }
        }
        List<RefundPreparationPlan> result = new ArrayList<>();
        for (RefundPaymentLedger ledger : ordered) {
            Payment payment = ledger.payment();
            if (payment.getProcessStatus() != PaymentProcessStatus.COMPLETED) { continue; }
            List<PaymentAllocation> allocations = new ArrayList<>(ledger.allocations());
            allocations.sort(Comparator.comparing(PaymentAllocation::getId));
            List<PaymentCancelAllocationTarget> allocated = new ArrayList<>();
            BigDecimal amount = BigDecimal.ZERO;
            for (PaymentAllocation allocation : allocations) {
                String registrationId = allocation.getRegistration().getId();
                BigDecimal need = needs.getOrDefault(registrationId, BigDecimal.ZERO);
                BigDecimal take = need.min(allocationRemaining.get(allocation.getId()));
                if (take.signum() == 0) { continue; }
                allocated.add(new PaymentCancelAllocationTarget(allocation, take));
                amount = amount.add(take);
                needs.put(registrationId, need.subtract(take));
            }
            if (amount.signum() == 0) { continue; }
            PaymentRefundBudget budget = paymentBudgets.get(payment.getId());
            budget.validateRequest(amount);
            result.add(new RefundPreparationPlan(payment, amount,
                    amount.compareTo(budget.remainingAmount()) == 0 ? PaymentCancelType.FULL : PaymentCancelType.PARTIAL,
                    allocated));
        }
        if (needs.values().stream().anyMatch(amount -> amount.signum() != 0)) { throw invalid(); }
        return List.copyOf(result);
    }

    /** 원 주문·원 귀속·취소·취소 귀속의 연결과 양쪽 한도를 모두 검증한다. */
    private void validateLedger(RefundPaymentLedger ledger, Map<String, BigDecimal> remaining,
                                Map<String, BigDecimal> netPaid, Map<String, PaymentRefundBudget> budgets) {
        Payment payment = ledger.payment();
        money(payment.getAmount(), true);
        if (payment.getPaymentKey() == null || payment.getPaymentKey().isBlank()
                || (payment.getRegistration() == null) == (payment.getOrganization() == null)) { throw invalid(); }
        Map<String, PaymentAllocation> originals = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (PaymentAllocation allocation : ledger.allocations()) {
            if (allocation == null || allocation.getId() == null || allocation.getPayment() == null
                    || !payment.getId().equals(allocation.getPayment().getId())
                    || allocation.getRegistration() == null || allocation.getRegistration().getId() == null
                    || originals.put(allocation.getId(), allocation) != null
                    || remaining.containsKey(allocation.getId())) { throw invalid(); }
            money(allocation.getAllocatedAmount(), false);
            Registration registration = allocation.getRegistration();
            if (payment.getRegistration() != null
                    && !payment.getRegistration().getId().equals(registration.getId())) { throw invalid(); }
            if (payment.getOrganization() != null && (registration.getOrganization() == null
                    || !payment.getOrganization().getId().equals(registration.getOrganization().getId()))) { throw invalid(); }
            total = total.add(allocation.getAllocatedAmount());
        }
        if (originals.isEmpty() || total.compareTo(payment.getAmount()) != 0
                || originals.values().stream().map(a -> a.getRegistration().getId()).distinct().count() != originals.size()) { throw invalid(); }
        Map<String, PaymentCancel> cancels = new HashMap<>();
        for (PaymentCancel cancellation : ledger.cancellations()) {
            if (cancellation == null || cancellation.getId() == null || cancellation.getPayment() == null
                    || !payment.getId().equals(cancellation.getPayment().getId()) || cancellation.getStatus() == null
                    || cancels.put(cancellation.getId(), cancellation) != null) { throw invalid(); }
            money(cancellation.getCancelAmount(), true);
            if (cancellation.getCancelAmount().compareTo(payment.getAmount()) > 0) { throw invalid(); }
        }
        PaymentRefundBudget paymentBudget = PaymentRefundBudget.calculate(payment.getAmount(),
                ledger.cancellations().stream().map(c -> new PaymentRefundBudget.CancelAmount(c.getStatus(), c.getCancelAmount())).toList());
        if (paymentBudget.unsettled()) { throw conflict(); }
        Map<String, List<PaymentRefundBudget.CancelAmount>> histories = new HashMap<>();
        Map<String, BigDecimal> cancelSums = new HashMap<>();
        Set<String> uniquePairs = new HashSet<>();
        Set<String> uniqueEntries = new HashSet<>();
        for (PaymentCancelAllocation allocation : ledger.cancelAllocations()) {
            if (allocation == null || allocation.getId() == null || !uniqueEntries.add(allocation.getId())
                    || allocation.getPaymentCancel() == null || allocation.getOriginalAllocation() == null) { throw invalid(); }
            String cancelId = allocation.getPaymentCancel().getId();
            String originalId = allocation.getOriginalAllocation().getId();
            PaymentCancel cancellation = cancels.get(cancelId);
            if (cancellation == null || !originals.containsKey(originalId)
                    || !uniquePairs.add(cancelId + ":" + originalId)) { throw invalid(); }
            money(allocation.getAllocatedAmount(), true);
            if (allocation.getAllocatedAmount().compareTo(originals.get(originalId).getAllocatedAmount()) > 0) { throw invalid(); }
            cancelSums.merge(cancelId, allocation.getAllocatedAmount(), BigDecimal::add);
            histories.computeIfAbsent(originalId, key -> new ArrayList<>()).add(
                    new PaymentRefundBudget.CancelAmount(cancellation.getStatus(), allocation.getAllocatedAmount()));
        }
        for (PaymentCancel cancellation : cancels.values()) {
            if (cancelSums.getOrDefault(cancellation.getId(), BigDecimal.ZERO)
                    .compareTo(cancellation.getCancelAmount()) != 0) { throw invalid(); }
        }
        for (PaymentAllocation allocation : originals.values()) {
            List<PaymentRefundBudget.CancelAmount> history = histories.getOrDefault(allocation.getId(), List.of());
            BigDecimal available;
            if (allocation.getAllocatedAmount().signum() == 0) {
                if (!history.isEmpty()) { throw invalid(); }
                available = BigDecimal.ZERO;
            } else {
                PaymentRefundBudget budget = PaymentRefundBudget.calculate(allocation.getAllocatedAmount(), history);
                if (budget.unsettled()) { throw conflict(); }
                available = budget.remainingAmount();
            }
            remaining.put(allocation.getId(), available);
            netPaid.merge(allocation.getRegistration().getId(), available, BigDecimal::add);
        }
        budgets.put(payment.getId(), paymentBudget);
    }

    /** DB 금액 정밀도로 표현할 수 있는 유효한 금액인지 확인한다. */
    private void money(BigDecimal value, boolean positive) {
        if (value == null || value.signum() < 0 || (positive && value.signum() == 0)
                || value.stripTrailingZeros().scale() > 2
                || value.abs().compareTo(new BigDecimal("10000000000")) >= 0) { throw invalid(); }
    }

    /** 원장 연결·금액 불일치를 업무 오류로 반환한다. */
    private CustomException invalid() { return new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR); }

    /** 새 환불 준비와 미확정 금융 처리의 충돌을 반환한다. */
    private CustomException conflict() { return new CustomException(ErrorCode.PAYMENT_CANCEL_CONFLICT); }
}
