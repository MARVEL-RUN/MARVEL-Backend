package kr.co.teambrain.marvelrun.user.event.command.application.dto;

import java.math.BigDecimal;

/**
 * 개인 신청의 정보·가격·Capacity 반영 결과를 전달한다.
 *
 * 외부 API의 최종 완료 응답이 아니다.
 * 같은 트랜잭션에서 금융 상태와 주문 처리를 이어가기 위한 내부 결과이다.
 *
 * price.contractDelta는 새 계약금액 - 기존 계약금액이다.
 * 실제 추가 결제·환불 필요금액은 새 계약금액과 paidAmount를 비교해야 한다.
 */
public record RegistrationPersonalModificationResult(
        String registrationId,
        RegistrationModificationPrice price,
        BigDecimal paidAmount
) {
}