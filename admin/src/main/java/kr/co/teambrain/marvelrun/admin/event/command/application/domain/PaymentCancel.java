package kr.co.teambrain.marvelrun.admin.event.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.PaymentCancelBase;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Payment;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import static lombok.AccessLevel.PROTECTED;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelPurpose;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import java.math.BigDecimal;

/** 원 Payment의 환불 시도와 외부 결과를 보존한다. */
@Getter
@Entity
@SuperBuilder
@Table(name = "payment_cancel")
@NoArgsConstructor(access = PROTECTED)
public class PaymentCancel
        extends PaymentCancelBase<Payment> {

    /**
     * 수정으로 줄어든 계약금액의 환불 시도를 준비한다.
     * 제거 구성원의 계약금액 0원 변경도 포함한다. 아직 외부 요청 시각은 기록하지 않는다.
     * 부모 잠금과 과거 환불 한도 검증은 준비 서비스가 선행한다.
     */
    public static PaymentCancel preparePriceAdjustment(
            Payment payment, BigDecimal amount,
            PaymentCancelType type,
            String idempotencyKey) {
        if (payment == null || payment.getId() == null
                || payment.getProcessStatus() != PaymentProcessStatus.COMPLETED
                || payment.getPaymentKey() == null || payment.getPaymentKey().isBlank()
                || amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2
                || amount.compareTo(new BigDecimal("10000000000")) >= 0
                || payment.getAmount() == null || amount.compareTo(payment.getAmount()) > 0
                || type == null || idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 300) {
            throw new CustomException(
                    ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
        }
        return PaymentCancel.builder().payment(payment).cancelAmount(amount).cancelType(type)
                .purpose(PaymentCancelPurpose.PRICE_ADJUSTMENT)
                .status(PaymentCancelStatus.PROCESSING)
                .cancelReason("신청 수정에 따른 초과 납부액 환불")
                .idempotencyKey(idempotencyKey).build();
    }

    /** 동일한 금액·원결제 검증을 사용하고 참가 자체 취소 목적과 PG 전송 사유를 기록한다. */
    public static PaymentCancel prepareRegistrationCancellation(
            Payment payment, BigDecimal amount, PaymentCancelType type, String idempotencyKey) {
        PaymentCancel prepared = preparePriceAdjustment(payment, amount, type, idempotencyKey);
        prepared.purpose = PaymentCancelPurpose.REGISTRATION_CANCELLATION;
        prepared.cancelReason = "참가 취소에 따른 잔여 납부액 환불";
        return prepared;
    }

    /** 준비된 시도 하나만 외부 전송 대상으로 획득한다. 이미 시작한 시도는 재전송하지 않는다. */
    public boolean startRefund(java.time.LocalDateTime now) {
        if (status != PaymentCancelStatus.PROCESSING || requestedAt != null) { return false; }
        if (now == null) { throw new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR); }
        requestedAt = now;
        return true;
    }

    /** 같은 거래 결과는 한 번만 적용하고 서로 다른 거래로 완료 상태를 덮어쓰지 않는다. */
    public boolean completeRefund(String key, BigDecimal amount, BigDecimal remaining,
                                  java.time.LocalDateTime occurredAt) {
        if (key == null || key.isBlank() || key.length() > 64 || amount == null
                || amount.signum() <= 0 || cancelAmount == null || amount.compareTo(cancelAmount) != 0
                || remaining == null || remaining.signum() < 0 || occurredAt == null) {
            throw new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
        }
        if (status == PaymentCancelStatus.DONE) {
            if (!key.equals(transactionKey) || refundableAmountAfterCancel == null
                    || remaining.compareTo(refundableAmountAfterCancel) != 0) {
                throw new CustomException(ErrorCode.PAYMENT_CANCEL_CONFLICT);
            }
            return false;
        }
        if (requestedAt == null || (status != PaymentCancelStatus.PROCESSING
                && status != PaymentCancelStatus.UNKNOWN)) {
            throw new CustomException(ErrorCode.PAYMENT_CANCEL_CONFLICT);
        }
        status = PaymentCancelStatus.DONE;
        transactionKey = key;
        refundableAmountAfterCancel = remaining;
        canceledAt = occurredAt;
        errorCode = null;
        errorMessage = null;
        return true;
    }

    /** 확정 완료를 보존하고 실패·결과불명만 기록한다. 결과불명을 뒤늦은 실패로 해제하지 않는다. */
    public boolean recordRefundProblem(PaymentCancelStatus next, String code, String message) {
        if (next != PaymentCancelStatus.FAILED && next != PaymentCancelStatus.UNKNOWN) {
            throw new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
        }
        if (status == PaymentCancelStatus.DONE || status == PaymentCancelStatus.FAILED
                || (status == PaymentCancelStatus.UNKNOWN && next == PaymentCancelStatus.FAILED)) { return false; }
        if (requestedAt == null || (status != PaymentCancelStatus.PROCESSING
                && status != PaymentCancelStatus.UNKNOWN)) {
            throw new CustomException(ErrorCode.PAYMENT_CANCEL_CONFLICT);
        }
        if (status == next && java.util.Objects.equals(errorCode, code)) { return false; }
        status = next;
        errorCode = code == null ? null : code.substring(0, Math.min(100, code.length()));
        errorMessage = message == null ? null : message.substring(0, Math.min(500, message.length()));
        return true;
    }
    /** 관리자 입력 사유만 기록한다. 원장 한도와 목적은 기존 준비 팩터리가 검증한다. */
    public void recordAdminReason(String reason) {
        if (status != PaymentCancelStatus.PROCESSING || requestedAt != null || reason == null
                || reason.isBlank() || reason.length() > 200) {
            throw new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
        }
        cancelReason = reason;
    }
}
