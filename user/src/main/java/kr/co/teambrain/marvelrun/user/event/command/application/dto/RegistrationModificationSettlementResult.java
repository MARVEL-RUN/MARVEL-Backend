package kr.co.teambrain.marvelrun.user.event.command.application.dto;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import java.math.BigDecimal;
import java.util.List;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;

/**
 * 신청 수정 후 금융 상태와 새 최초·추가 결제 주문을 전달한다.
 *
 * 참가자별 balance는 금액 상태이며, 결제창 진입 정보는 orders로 제공한다.
 * 실제 승인·환불이 완료되었다는 의미는 아니다.
 */
public record RegistrationModificationSettlementResult(
        List<Member> members,
        List<Order> orders,
        List<Refund> refunds
) {

    /**
     * 처리 결과 목록을 외부 변경으로부터 보호한다.
     */
    public RegistrationModificationSettlementResult {
        members = List.copyOf(members);
        orders = List.copyOf(orders);
        refunds = List.copyOf(refunds);
    }

    /** 환불 준비가 없는 기존 응답과 개인정보 전용 경로의 생성 계약을 유지한다. */
    public RegistrationModificationSettlementResult(List<Member> members, List<Order> orders) {
        this(members, orders, List.of());
    }

    /** 서버가 준비한 환불 시도이다. PROCESSING은 환불 성공이나 외부 요청 완료를 뜻하지 않는다. */
    public record Refund(String paymentCancelId, String paymentId, BigDecimal amount,
                         PaymentCancelStatus status,
                         String correlationId) { }

    /**
     * 수정된 참가자 한 명의 계약금액·순결제금액·미정산 차액이다.
     *
     * balance > 0: 결제 필요
     * balance < 0: 환불 필요
     * balance == 0: 금액 정산 완료
     */
    public record Member(
            String registrationId,
            RegistrationStatus status,
            BigDecimal contractAmount,
            BigDecimal paidAmount,
            BigDecimal balance
    ) {
    }

    /**
     * 수정 후 최초 미결제 또는 추가 납부를 위해 새로 생성한 주문이다.
     * 클라이언트는 주문 목적에 따라 승인 API를 나누지 않고 공통 결제 흐름을 사용한다.
     */
    public record Order(
            String paymentId,
            String orderId,
            String orderName,
            BigDecimal amount
    ) {
    }
}