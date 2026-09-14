package kr.co.teambrain.marvelrun.common.json_object;

import kr.co.teambrain.marvelrun.common.inheritance_enum.EventStatus;

import java.util.List;

/**
 * 이벤트 벌크 업데이트
 */
public record EventPhoneAuthBulkUpdateAuditDetail(
        boolean phoneAuthRequired,
        List<EventStatus> eligibleStatuses
) implements PhoneAuthPolicyAuditDetail {
}