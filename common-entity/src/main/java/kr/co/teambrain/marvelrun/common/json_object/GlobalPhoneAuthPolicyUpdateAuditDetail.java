package kr.co.teambrain.marvelrun.common.json_object;

import kr.co.teambrain.marvelrun.common.inheritance_enum.phone_auth_policy.PhoneAuthGlobalPolicy;

public record GlobalPhoneAuthPolicyUpdateAuditDetail(
        PhoneAuthGlobalPolicy beforePolicy,
        PhoneAuthGlobalPolicy afterPolicy
) implements PhoneAuthPolicyAuditDetail {
}