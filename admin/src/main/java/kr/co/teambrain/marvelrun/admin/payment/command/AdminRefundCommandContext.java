package kr.co.teambrain.marvelrun.admin.payment.command;
/** 인증된 관리자 진입점이 제공하는 추적정보다. 금액이나 시간은 외부 입력으로 받지 않는다. */
public record AdminRefundCommandContext(String requestId, String adminId, String reason) {
    public AdminRefundCommandContext {
        if (requestId == null || requestId.isBlank() || requestId.length() > 64
                || adminId == null || adminId.isBlank() || adminId.length() > 64
                || reason == null || reason.isBlank() || reason.length() > 200) {
            throw new IllegalArgumentException("관리자 환불 추적정보가 올바르지 않습니다.");
        }
    }
}
