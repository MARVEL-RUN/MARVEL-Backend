package kr.co.teambrain.marvelrun.user.event.query.dto.response;

import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyModels;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationRestrictionReason;

/** 접수 제한 코드와 실제 KST 제한 기간을 포함한 표시 문구다. */
public record RegistrationRestrictionReasonResponse(RegistrationRestrictionReason code, String message) {
    /** 허용이면 null을 반환하고 제한이면 전용 문구와 판정된 기간을 조립한다. */
    public static RegistrationRestrictionReasonResponse fromDecision(RegistrationActionPolicyModels.Decision decision) {
        if (decision.allowed()) { return null; }
        RegistrationRestrictionReason code = RegistrationRestrictionReason.valueOf(decision.reason().name());
        return new RegistrationRestrictionReasonResponse(code, code.message() + decision.restrictionPeriodMessage());
    }
}
