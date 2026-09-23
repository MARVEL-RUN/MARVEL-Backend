package kr.co.teambrain.marvelrun.admin.payment.command.application.dto;

import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelType;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Payment;
import java.math.BigDecimal;
import java.util.List;

/** 원 결제 하나에 대해 검증을 마친 취소 금액과 참가자별 귀속이다. 외부 요청 DTO가 아니다. */
public record RefundPreparationPlan(Payment payment, BigDecimal amount, PaymentCancelType type,
                                    List<PaymentCancelAllocationTarget> targets) {
    /** 준비 시 결정한 귀속 목록을 보존한다. */
    public RefundPreparationPlan { targets = List.copyOf(targets); }
}