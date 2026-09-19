package kr.co.teambrain.marvelrun.user.payment.command.application.dto;

import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;

import java.math.BigDecimal;

/**
 * 하나의 Payment에서 특정 Registration에 귀속시킬 금액을 전달한다.
 *
 * 04 최초 단체결제에서는 Registration.contractAmount를 전달하고,
 * 07 추가결제에서는 실제 추가결제 delta 금액을 전달한다.
 *
 * @param registration 금액 귀속 대상 신청
 * @param amount 해당 Payment에서 귀속할 금액
 */
public record PaymentAllocationTarget(
        Registration registration,
        BigDecimal amount
) {
}