package kr.co.teambrain.marvelrun.user.event.query.support;

import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.PaymentCancel;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.query.dto.RegistrationReceiptResponse.DisplayStatus;
import kr.co.teambrain.marvelrun.user.event.query.dto.RegistrationReceiptResponse.PaymentAction;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.PaymentAllocation;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.PaymentCancelAllocation;
import kr.co.teambrain.marvelrun.user.payment.command.application.valid.EventPaymentPolicyValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 조회 시점의 결제 안내만 계산하며 재준비·승인·환불·잠금 처리는 수행하지 않는다. */
@Component
@RequiredArgsConstructor
public class RegistrationReceiptPaymentResolver {
    private final EventPaymentPolicyValidator policyValidator;

    /** 금융 원장과 현재 금액, 진행 중 요청, 유효 주문 후보를 순서대로 확인한다. */
    public View resolve(Event event, List<Registration> members, List<Payment> payments,
            List<PaymentAllocation> allocations, List<PaymentCancel> refunds,
            List<PaymentCancelAllocation> refundAllocations,
            Map<String, Reservation> reservations, LocalDateTime now) {
        String inquiryOrderId = payments.isEmpty() ? null : payments.get(0).getOrderId();
        if (!consistent(members, payments, allocations, refunds, refundAllocations)) {
            return view(DisplayStatus.DATA_CHECK_REQUIRED, PaymentAction.CONTACT_SUPPORT, null,
                    inquiryOrderId, "저장된 납부 금액과 결제·환불 내역 확인이 필요합니다. 문의해 주세요.");
        }
        if (payments.stream().anyMatch(p -> is(p.getProcessStatus(), "UNKNOWN"))) {
            return view(DisplayStatus.PAYMENT_UNKNOWN, PaymentAction.WAIT, null, inquiryOrderId,
                    "결제 결과를 확인 중입니다. 새 결제를 시도하지 마세요.");
        }
        if (payments.stream().anyMatch(p -> is(p.getProcessStatus(), "CONFIRMING"))) {
            return view(DisplayStatus.PAYMENT_PROCESSING, PaymentAction.WAIT, null, inquiryOrderId,
                    "결제 승인을 처리 중입니다.");
        }
        if (refunds.stream().anyMatch(r -> is(r.getStatus(), "UNKNOWN"))) {
            return view(DisplayStatus.REFUND_UNKNOWN, PaymentAction.WAIT, null, inquiryOrderId,
                    "환불 결과를 확인 중입니다. 새 결제를 시도하지 마세요.");
        }
        if (refunds.stream().anyMatch(r -> is(r.getStatus(), "PROCESSING"))) {
            return view(DisplayStatus.REFUND_PROCESSING, PaymentAction.WAIT, null, inquiryOrderId,
                    "환불 처리 중입니다. 완료 전까지 현재 납부 금액이 남을 수 있습니다.");
        }
        Set<String> unpaidIds = new HashSet<>();
        boolean refundNeeded = false;
        boolean additional = false;
        boolean allCanceled = true;
        for (Registration member : members) {
            refundNeeded |= member.getPaidAmount().compareTo(member.getContractAmount()) > 0;
            if (!member.isSoftDeleted()) {
                allCanceled = false;
                if (member.getContractAmount().compareTo(member.getPaidAmount()) > 0) {
                    unpaidIds.add(member.getId());
                    additional |= is(member.getStatus(), "ADDITIONAL_PAYMENT_REQUIRED");
                }
            }
        }
        boolean refundFailed = refundNeeded
                && refunds.stream().anyMatch(r -> is(r.getStatus(), "FAILED"));
        DisplayStatus status = allCanceled
                ? (refundNeeded ? DisplayStatus.CANCELLATION_PENDING : DisplayStatus.CANCELED)
                : refundFailed ? DisplayStatus.REFUND_FAILED
                : refundNeeded ? DisplayStatus.REFUND_REQUIRED
                : unpaidIds.isEmpty() ? DisplayStatus.CONFIRMED
                : additional ? DisplayStatus.ADDITIONAL_PAYMENT_REQUIRED : DisplayStatus.PAYMENT_PENDING;
        String warning = refundFailed ? "환불이 완료되지 않았습니다. 운영자 확인이 필요합니다."
                : refundNeeded ? "환불할 금액이 남아 있습니다. 환불 완료 여부를 확인하세요."
                : unpaidIds.isEmpty() ? null : "미납 금액이 있습니다. 결제 기한 내 결제를 완료해 주세요.";
        if (!unpaidIds.isEmpty() && refundNeeded) {
            warning += " 별도로 미납 금액이 있으므로 결제 안내도 확인해 주세요.";
        }
        if (unpaidIds.isEmpty()) {
            return view(status, refundFailed ? PaymentAction.CONTACT_SUPPORT : PaymentAction.NONE,
                    null, inquiryOrderId, warning);
        }
        try {
            policyValidator.validateNewPayment(event, now);
        } catch (CustomException exception) {
            if (exception.getErrorCode() == ErrorCode.EVENT_PAYMENT_CLOSED) {
                return view(status, PaymentAction.PAYMENT_CLOSED, null, inquiryOrderId,
                        "결제 기한이 지났습니다. 운영자에게 문의해 주세요.");
            }
            if (exception.getErrorCode() == ErrorCode.PAYMENT_POLICY_CONFIGURATION_ERROR) {
                return view(DisplayStatus.DATA_CHECK_REQUIRED, PaymentAction.CONTACT_SUPPORT, null,
                        inquiryOrderId, "결제 기한 설정 확인이 필요합니다.");
            }
            throw exception;
        }
        Map<String, List<PaymentAllocation>> byPayment = new HashMap<>();
        for (PaymentAllocation allocation : allocations) {
            byPayment.computeIfAbsent(allocation.getPayment().getId(), key -> new ArrayList<>())
                    .add(allocation);
        }
        List<Payment> ready = payments.stream().filter(p -> is(p.getProcessStatus(), "READY")).toList();
        Payment candidate = null;
        // 유효하지 않은 READY를 건너뛰어 과거 실패 주문으로 우회하지 않는다.
        if (ready.size() == 1 && eligible(ready.get(0), byPayment.getOrDefault(
                ready.get(0).getId(), List.of()), unpaidIds, reservations)) {
            candidate = ready.get(0);
        } else if (ready.isEmpty()) {
            for (Payment payment : payments) {
                if ((is(payment.getProcessStatus(), "FAILED") || is(payment.getProcessStatus(), "INVALIDATED"))
                        && eligible(payment, byPayment.getOrDefault(payment.getId(), List.of()),
                                unpaidIds, reservations)) {
                    candidate = payment;
                    break;
                }
            }
        }
        if (candidate == null) {
            return view(status, PaymentAction.CONTACT_SUPPORT, null, inquiryOrderId,
                    "미납 내역에 대응하는 주문을 확인할 수 없습니다. 운영자에게 문의해 주세요.");
        }
        return view(status, PaymentAction.PREPARE_PAYMENT, candidate.getId(), candidate.getOrderId(), warning);
    }

    /** 승인·환불 성공 귀속의 순액과 Registration의 저장 요약을 비교하며 잘못된 값을 숨기지 않는다. */
    private boolean consistent(List<Registration> members, List<Payment> payments,
            List<PaymentAllocation> allocations, List<PaymentCancel> refunds,
            List<PaymentCancelAllocation> refundAllocations) {
        Map<String, BigDecimal> net = new HashMap<>();
        for (Registration member : members) {
            if (member.getContractAmount() == null || member.getPaidAmount() == null
                    || member.getContractAmount().signum() < 0 || member.getPaidAmount().signum() < 0) {
                return false;
            }
            net.put(member.getId(), BigDecimal.ZERO);
        }
        Map<String, BigDecimal> paymentTotals = new HashMap<>();
        for (PaymentAllocation allocation : allocations) {
            if (!is(allocation.getPayment().getProcessStatus(), "COMPLETED")) {
                continue;
            }
            String id = allocation.getRegistration().getId();
            if (!net.containsKey(id) || allocation.getAllocatedAmount() == null
                    || allocation.getAllocatedAmount().signum() < 0) {
                return false;
            }
            net.merge(id, allocation.getAllocatedAmount(), BigDecimal::add);
            paymentTotals.merge(allocation.getPayment().getId(), allocation.getAllocatedAmount(), BigDecimal::add);
        }
        Map<String, BigDecimal> refundTotals = new HashMap<>();
        for (PaymentCancelAllocation allocation : refundAllocations) {
            if (!is(allocation.getPaymentCancel().getStatus(), "DONE")) {
                continue;
            }
            PaymentAllocation original = allocation.getOriginalAllocation();
            String id = original.getRegistration().getId();
            if (!net.containsKey(id) || allocation.getAllocatedAmount() == null
                    || allocation.getAllocatedAmount().signum() <= 0
                    || !Objects.equals(original.getPayment().getId(),
                        allocation.getPaymentCancel().getPayment().getId())) {
                return false;
            }
            net.merge(id, allocation.getAllocatedAmount().negate(), BigDecimal::add);
            refundTotals.merge(allocation.getPaymentCancel().getId(), allocation.getAllocatedAmount(), BigDecimal::add);
        }
        for (Payment payment : payments) {
            if (is(payment.getProcessStatus(), "COMPLETED") && (payment.getAmount() == null
                    || paymentTotals.getOrDefault(payment.getId(), BigDecimal.ZERO)
                        .compareTo(payment.getAmount()) != 0)) {
                return false;
            }
        }
        for (PaymentCancel refund : refunds) {
            if (is(refund.getStatus(), "DONE") && (refund.getCancelAmount() == null
                    || refundTotals.getOrDefault(refund.getId(), BigDecimal.ZERO)
                        .compareTo(refund.getCancelAmount()) != 0)) {
                return false;
            }
        }
        return members.stream().allMatch(r -> net.get(r.getId()).compareTo(r.getPaidAmount()) == 0);
    }

    /** 현재 미납 대상·금액·귀속 목적·예약 상태가 맞는 주문만 재준비 후보로 표시한다. */
    private boolean eligible(Payment payment, List<PaymentAllocation> allocations,
            Set<String> unpaidIds, Map<String, Reservation> reservations) {
        if (payment.isOrgPayment() == payment.isRegistrationPayment()
                || allocations.isEmpty() || payment.getAmount() == null || payment.getAmount().signum() <= 0) {
            return false;
        }
        Set<String> positiveIds = new HashSet<>();
        Set<String> allIds = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean initialFound = false;
        boolean additionalFound = false;
        for (PaymentAllocation allocation : allocations) {
            Registration registration = allocation.getRegistration();
            BigDecimal amount = allocation.getAllocatedAmount();
            if (!Objects.equals(payment.getId(), allocation.getPayment().getId())
                    || registration.isSoftDeleted() || !allIds.add(registration.getId())
                    || amount == null || amount.signum() < 0
                    || registration.getContractAmount() == null || registration.getPaidAmount() == null
                    || registration.getContractAmount().subtract(registration.getPaidAmount()).compareTo(amount) != 0
                    || (payment.isOrgPayment() && (registration.getOrganization() == null
                        || !Objects.equals(payment.getOrganization().getId(), registration.getOrganization().getId())
                        || !Objects.equals(payment.getOrganization().getEvent().getId(), registration.getEvent().getId())))
                    || (payment.isRegistrationPayment() && (!Objects.equals(payment.getRegistration().getId(), registration.getId())
                        || registration.getOrganization() != null))) {
                return false;
            }
            String purpose;
            try {
                purpose = allocation.effectivePurpose().name();
            } catch (CustomException exception) {
                return false;
            }
            Reservation reservation = reservations.get(registration.getId());
            if (reservation == null) {
                return false;
            }
            if (purpose.equals("REGISTRATION_TRY")) {
                initialFound = true;
                if (registration.getPaidAmount().signum() != 0 || !is(registration.getStatus(), "PAYMENT_PENDING")
                        || (!is(reservation.getStatus(), "HELD")
                            && (is(payment.getProcessStatus(), "READY") || !is(reservation.getStatus(), "RELEASED")))) {
                    return false;
                }
            } else {
                additionalFound = true;
                if (amount.signum() <= 0 || !is(registration.getStatus(), "ADDITIONAL_PAYMENT_REQUIRED")
                        || !is(reservation.getStatus(), "CONSUMED")) {
                    return false;
                }
            }
            if (amount.signum() > 0) {
                positiveIds.add(registration.getId());
            }
            total = total.add(amount);
        }
        if (is(payment.getPurpose(), "MIXED_PAYMENT") && (!payment.isOrgPayment()
                || !initialFound || !additionalFound || allocations.stream().anyMatch(a -> a.getAllocatedAmount().signum() <= 0))) {
            return false;
        }
        return positiveIds.equals(unpaidIds) && total.compareTo(payment.getAmount()) == 0;
    }

    /** 문자열 변환은 응답용 enum만 사용하고 엔티티의 값을 변경하지 않는다. */
    private boolean is(Enum<?> value, String name) {
        return value != null && value.name().equals(name);
    }

    /** 화면 상태와 행동을 따로 반환해 프론트의 문구 기반 버튼 판정을 없앤다. */
    private View view(DisplayStatus status, PaymentAction action, String paymentId,
            String orderId, String warning) {
        String label = switch (status) {
            case PAYMENT_PENDING -> "결제 대기";
            case ADDITIONAL_PAYMENT_REQUIRED -> "추가 결제 필요";
            case PAYMENT_PROCESSING -> "결제 처리 중";
            case PAYMENT_UNKNOWN -> "결제 확인 중";
            case CONFIRMED -> "참가 확정";
            case REFUND_REQUIRED -> "환불 필요";
            case REFUND_PROCESSING -> "환불 처리 중";
            case REFUND_UNKNOWN -> "환불 확인 중";
            case REFUND_FAILED -> "환불 미완료";
            case CANCELLATION_PENDING -> "참가 취소 · 환불 대기";
            case CANCELED -> "참가 취소 완료";
            case DATA_CHECK_REQUIRED -> "금액 확인 필요";
        };
        return new View(status, label, warning, action, paymentId, orderId);
    }

    /** 조회용 표시 결과이며 paymentId가 있어도 승인·재준비의 검증을 대체하지 않는다. */
    public record View(DisplayStatus status, String label, String warning,
            PaymentAction action, String paymentId, String orderId) { }
}
