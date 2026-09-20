package kr.co.teambrain.marvelrun.user.event.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.PaymentCancelBase;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import static lombok.AccessLevel.PROTECTED;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelPurpose;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
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
}

