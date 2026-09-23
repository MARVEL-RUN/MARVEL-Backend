package kr.co.teambrain.marvelrun.admin.payment.command;
/** 인증된 관리자 진입점이 제공하는 추적정보다. 금액이나 시간은 외부 입력으로 받지 않는다. */
public record AdminRefundCommandContext(String requestId, String adminId, String reason, Long expectedRegistrationVersion, String batchId, Integer batchItemNo) {
    /** 기존 즉시 실행 호출은 별도 접수 스냅샷 없이 동일 트랜잭션 검증을 사용한다. */
    public AdminRefundCommandContext(String requestId, String adminId, String reason) {
        this(requestId, adminId, reason, null, null, null);
    }
    /** 기존 버전 검증 호출의 생성자 호환성을 보존한다. */
    public AdminRefundCommandContext(String requestId, String adminId, String reason, Long expectedRegistrationVersion) {
        this(requestId, adminId, reason, expectedRegistrationVersion, null, null);
    }
    public AdminRefundCommandContext {
        if ((batchId == null) != (batchItemNo == null) || (batchItemNo != null && batchItemNo < 0)) {
            throw new IllegalArgumentException("배치 연결정보가 올바르지 않습니다.");
        }
        if (requestId == null || requestId.isBlank() || requestId.length() > 64
                || adminId == null || adminId.isBlank() || adminId.length() > 64
                || reason == null || reason.isBlank() || reason.length() > 200) {
            throw new IllegalArgumentException("관리자 환불 추적정보가 올바르지 않습니다.");
        }
    }
}
