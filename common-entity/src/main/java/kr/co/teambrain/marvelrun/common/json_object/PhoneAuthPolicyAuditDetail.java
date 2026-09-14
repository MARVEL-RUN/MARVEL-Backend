package kr.co.teambrain.marvelrun.common.json_object;

public sealed interface PhoneAuthPolicyAuditDetail
        permits EventPhoneAuthBulkUpdateAuditDetail, GlobalPhoneAuthPolicyUpdateAuditDetail {
}