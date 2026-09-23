package kr.co.teambrain.marvelrun.user.event.query.support;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.user.event.query.dto.RegistrationPaymentAction;
import kr.co.teambrain.marvelrun.user.event.query.repository.RegistrationQueryData;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 읽은 프로젝션만으로 결제 재준비 버튼을 안내한다. 실제 허가는 기존 retry가 재검증한다. */
@Component
public class RegistrationPaymentQueryResolver {
    /** 진행 중 요청을 우선 차단하고 현재 미납 전체에 맞는 주문을 선택한다. */
    public Result resolve(List<RegistrationQueryData.Member> members,
            List<RegistrationQueryData.Payment> payments,
            List<RegistrationQueryData.Allocation> allocations,
            List<RegistrationQueryData.Refund> refunds, LocalDateTime deadline, LocalDateTime now) {
        RegistrationQueryData.Payment latest = payments.isEmpty() ? null : payments.get(0);
        PaymentProcessStatus status = latest == null ? null : latest.status();
        String orderId = latest == null ? null : latest.orderId();
        PaymentCancelStatus refundStatus = refundStatus(refunds);
        for (PaymentProcessStatus pending : List.of(PaymentProcessStatus.UNKNOWN, PaymentProcessStatus.CONFIRMING)) {
            if (payments.stream().anyMatch(p -> p.status() == pending)) {
                return new Result(pending, refundStatus, RegistrationPaymentAction.WAIT,
                        "결제 결과를 확인 중입니다. 새 결제를 시도하지 마세요.", null, orderId);
            }
        }
        if (refundStatus == PaymentCancelStatus.UNKNOWN || refundStatus == PaymentCancelStatus.PROCESSING) {
            return new Result(status, refundStatus, RegistrationPaymentAction.WAIT,
                    "환불 결과를 확인 중입니다.", null, orderId);
        }
        Map<String, RegistrationQueryData.Member> current = new HashMap<>();
        Set<String> unpaid = new HashSet<>();
        for (RegistrationQueryData.Member member : members) {
            current.put(member.id(), member);
            if (!member.deleted() && member.contractAmount().compareTo(member.paidAmount()) > 0) {
                unpaid.add(member.id());
            }
        }
        if (unpaid.isEmpty()) {
            return new Result(status, refundStatus, RegistrationPaymentAction.NONE, null, null, orderId);
        }
        Set<String> additionalIds = unpaid.stream().filter(id -> {
            var row = current.get(id);
            return row.status() == RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED
                    && row.reservationStatus() == ReservationStatus.CONSUMED;
        }).collect(java.util.stream.Collectors.toSet());
        boolean paymentWindowOpen = deadline != null && now.isBefore(deadline);
        if (deadline == null && additionalIds.isEmpty()) {
            return new Result(status, refundStatus, RegistrationPaymentAction.CONTACT_SUPPORT,
                    "결제 기한 설정 확인이 필요합니다.", null, orderId);
        }
        if (!paymentWindowOpen && additionalIds.isEmpty()) {
            return new Result(status, refundStatus, RegistrationPaymentAction.PAYMENT_CLOSED,
                    "미납 금액이 있으나 결제 기한이 지났습니다.", null, orderId);
        }
        Map<String, List<RegistrationQueryData.Allocation>> byPayment = allocations.stream()
                .collect(java.util.stream.Collectors.groupingBy(RegistrationQueryData.Allocation::paymentId));
        List<RegistrationQueryData.Payment> ready = payments.stream()
                .filter(p -> p.status() == PaymentProcessStatus.READY).toList();
        RegistrationQueryData.Payment candidate = null;
        if (ready.size() == 1 && (paymentWindowOpen || ready.getFirst().purpose() == PaymentPurpose.ADDITIONAL_PAYMENT)
                && eligible(ready.get(0), byPayment.getOrDefault(ready.get(0).id(), List.of()), current,
                        ready.getFirst().purpose() == PaymentPurpose.ADDITIONAL_PAYMENT ? additionalIds : unpaid)) {
            candidate = ready.get(0);
        } else if (ready.isEmpty()) {
            for (RegistrationQueryData.Payment payment : payments) {
                if ((payment.status() == PaymentProcessStatus.FAILED || payment.status() == PaymentProcessStatus.INVALIDATED)
                        && (paymentWindowOpen || payment.purpose() == PaymentPurpose.ADDITIONAL_PAYMENT)
                        && eligible(payment, byPayment.getOrDefault(payment.id(), List.of()), current,
                            payment.purpose() == PaymentPurpose.ADDITIONAL_PAYMENT ? additionalIds : unpaid)) {
                    candidate = payment;
                    break;
                }
            }
        }
        if (candidate == null && ready.isEmpty() && !additionalIds.isEmpty()) {
            return new Result(status, refundStatus, RegistrationPaymentAction.PREPARE_ADDITIONAL_PAYMENT,
                    "신청 정보 변경으로 추가 납부가 필요합니다. 추가 결제를 준비해 주세요. 최초 미결제 인원은 별도 처리됩니다.", null, null);
        }
        if (candidate == null) {
            return new Result(status, refundStatus, RegistrationPaymentAction.CONTACT_SUPPORT,
                    "미납 내역에 맞는 주문을 확인할 수 없습니다. 문의해 주세요.", null, orderId);
        }
        return new Result(candidate.status(), refundStatus, RegistrationPaymentAction.PREPARE_PAYMENT,
                candidate.purpose() == PaymentPurpose.ADDITIONAL_PAYMENT
                        ? "확정 신청의 추가 납부가 필요합니다. 추가 결제를 완료해 주세요."
                        : "미납 금액이 있습니다. 결제 기한 내 결제를 완료해 주세요.", candidate.id(), candidate.orderId());
    }

    /** 귀속과 현재 값의 대응만 확인한다. 가격 재계산·재고 확보·정산은 하지 않는다. */
    private boolean eligible(RegistrationQueryData.Payment payment,
            List<RegistrationQueryData.Allocation> allocations,
            Map<String, RegistrationQueryData.Member> current, Set<String> unpaid) {
        if (allocations.isEmpty() || payment.amount().signum() <= 0) { return false; }
        BigDecimal sum = BigDecimal.ZERO;
        Set<String> ids = new HashSet<>();
        Set<String> positive = new HashSet<>();
        boolean initial = false;
        boolean additional = false;
        for (RegistrationQueryData.Allocation allocation : allocations) {
            RegistrationQueryData.Member member = current.get(allocation.registrationId());
            if (member == null || member.deleted() || !ids.add(member.id())
                    || allocation.amount().signum() < 0
                    || member.contractAmount().subtract(member.paidAmount()).compareTo(allocation.amount()) != 0) {
                return false;
            }
            PaymentPurpose purpose = allocation.purpose() == null ? payment.purpose() : allocation.purpose();
            if (payment.purpose() != PaymentPurpose.MIXED_PAYMENT && payment.purpose() != purpose) { return false; }
            if (purpose == PaymentPurpose.REGISTRATION_TRY) {
                initial = true;
                if (member.status() != RegistrationStatus.PAYMENT_PENDING || member.paidAmount().signum() != 0
                        || (member.reservationStatus() != ReservationStatus.HELD
                            && (payment.status() == PaymentProcessStatus.READY
                                || member.reservationStatus() != ReservationStatus.RELEASED))) { return false; }
            } else if (purpose == PaymentPurpose.ADDITIONAL_PAYMENT) {
                additional = true;
                if (member.status() != RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED
                        || allocation.amount().signum() <= 0
                        || member.reservationStatus() != ReservationStatus.CONSUMED) { return false; }
            } else { return false; }
            if (allocation.amount().signum() > 0) { positive.add(member.id()); }
            sum = sum.add(allocation.amount());
        }
        if (payment.purpose() == PaymentPurpose.MIXED_PAYMENT && (!initial || !additional
                || allocations.stream().anyMatch(a -> a.amount().signum() <= 0))) { return false; }
        return positive.equals(unpaid) && sum.compareTo(payment.amount()) == 0;
    }

    /** 진행·불명 상태를 우선하고 나머지는 가장 최근 환불의 기존 DB enum을 반환한다. */
    private PaymentCancelStatus refundStatus(List<RegistrationQueryData.Refund> refunds) {
        for (PaymentCancelStatus pending : List.of(PaymentCancelStatus.UNKNOWN, PaymentCancelStatus.PROCESSING)) {
            if (refunds.stream().anyMatch(r -> r.status() == pending)) { return pending; }
        }
        return refunds.isEmpty() ? null : refunds.get(0).status();
    }

    /** 사용자 응답에 필요한 결제·환불 enum과 후속 동작만 담는다. */
    public record Result(PaymentProcessStatus status, PaymentCancelStatus refundStatus,
            RegistrationPaymentAction action, String warning, String paymentId, String orderId) { }
}
