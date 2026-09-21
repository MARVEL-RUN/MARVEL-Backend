package kr.co.teambrain.marvelrun.user.event.command.application.dto;

import java.math.BigDecimal;

/**
 * 수정 후보의 가격 계산 결과다.
 *
 * 기존 참가자는 이전 계약금액, 새 계약금액, 계약금액 변경분을 가진다.
 * 신규 참가자는 이전 계약이 없으므로 이전 금액과 변경분이 null이다.
 *
 * 실제 추가결제액이나 환불액을 확정하는 금융 처리 결과는 아니다.
 */
public record RegistrationModificationPrice(
        BigDecimal oldContractAmount,
        BigDecimal newContractAmount,
        BigDecimal contractDelta
) {

    /**
     * 기존 참가자의 이전 계약금액과 새 계약금액을 비교한다.
     */
    public static RegistrationModificationPrice forExisting(
            BigDecimal oldContractAmount,
            BigDecimal newContractAmount
    ) {
        return new RegistrationModificationPrice(
                oldContractAmount,
                newContractAmount,
                newContractAmount.subtract(oldContractAmount)
        );
    }

    /**
     * 이전 계약이 없는 신규 참가자의 최초 계약금액을 보관한다.
     */
    public static RegistrationModificationPrice forNew(
            BigDecimal newContractAmount
    ) {
        return new RegistrationModificationPrice(
                null,
                newContractAmount,
                null
        );
    }
}