package kr.co.teambrain.marvelrun.user.event.command.application.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 단체 수정의 참가자별 변경 결과를 후속 금융 처리에 전달한다.
 *
 * 제거된 Registration도 포함하여 환불 대상을 보존한다.
 * 외부 API의 최종 완료 응답은 아니다.
 */
public record OrgRegistrationModificationResult(
        String organizationId,
        List<Member> members
) {

    /**
     * 참가자별 결과 목록을 외부 변경으로부터 보호한다.
     */
    public OrgRegistrationModificationResult {
        members = List.copyOf(members);
    }

    /**
     * 수정 후 활성 목록과 금융 처리 대상을 구분한다.
     */
    public enum Change {
        EXISTING,
        ADDED,
        REMOVED
    }

    /**
     * 참가자 한 명의 계약금액 변화와 실제 순결제금액이다.
     *
     * REMOVED의 새 계약금액은 0이며 paidAmount는 환불 전 금액을 유지한다.
     */
    public record Member(
            String registrationId,
            Change change,
            RegistrationModificationPrice price,
            BigDecimal paidAmount
    ) {
    }
}