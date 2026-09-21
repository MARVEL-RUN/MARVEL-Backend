package kr.co.teambrain.marvelrun.user.payment.command.application.dto;

import java.math.BigDecimal;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;

/** 참가자별 납부 금액과 최초/추가 결제 목적을 전달한다. */
public record PaymentAllocationTarget(
        Registration registration, BigDecimal amount, PaymentPurpose allocationPurpose) {
    /** 기존 단일 목적 주문의 호출 계약을 유지하며 목적은 생성기에서 부모 주문으로 결정한다. */
    public PaymentAllocationTarget(Registration registration, BigDecimal amount) {
        this(registration, amount, null);
    }
}
