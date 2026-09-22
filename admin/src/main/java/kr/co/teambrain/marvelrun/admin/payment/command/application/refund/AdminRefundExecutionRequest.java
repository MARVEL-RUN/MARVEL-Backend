package kr.co.teambrain.marvelrun.admin.payment.command.application.refund;
import java.math.BigDecimal;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
/** 커밋된 준비 결과에서만 만드는 내부 실행 입력이다. HTTP 요청 DTO로 사용하지 않는다. */
public final class AdminRefundExecutionRequest {
    /** 인스턴스 생성을 막는다. */
    private AdminRefundExecutionRequest() { }
    /** 기존 사용자 실행 계약과 동일한 취소·원결제·금액·추적 정보를 전달한다. */
    public record Refund(String paymentCancelId, String paymentId, BigDecimal amount,
            PaymentCancelStatus status, String correlationId) { }
}
