package kr.co.teambrain.marvelrun.admin.payment.command;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelType;
/** 커밋 후 외부 실행기로 넘길 준비 결과다. PROCESSING을 환불 완료로 해석하지 않는다. */
public record AdminRefundPrepared(String requestId, String correlationId, String eventId, String organizationId,
        LocalDateTime preparedAt, List<Member> members, List<Refund> refunds) {
    public AdminRefundPrepared { members = List.copyOf(members); refunds = List.copyOf(refunds); }
    /** 실제 납부액은 아직 줄지 않았으며, 참가 취소 여부와 예약 상태를 함께 표시한다. */
    public record Member(String registrationId, BigDecimal previousContractAmount, BigDecimal contractAmount,
            BigDecimal paidAmount, RegistrationStatus status, ReservationStatus reservationStatus, boolean participationCanceled,
            String previousBirth, String birth, String previousEventCategoryId, String eventCategoryId) {
        /** 과거 내부 호출의 준비 스냅샷 계약을 유지한다. */
        public Member(String registrationId, BigDecimal previousContractAmount, BigDecimal contractAmount,
                BigDecimal paidAmount, RegistrationStatus status, ReservationStatus reservationStatus, boolean participationCanceled) {
            this(registrationId, previousContractAmount, contractAmount, paidAmount, status, reservationStatus,
                    participationCanceled, null, null, null, null);
        }
        /** 이전 계약 대비 이번 변경액이며 이미 납부한 금액과는 구분한다. */
        @com.fasterxml.jackson.annotation.JsonProperty("contractAmountChange")
        public BigDecimal contractAmountChange() { return contractAmount.subtract(previousContractAmount); }
        /** 누적 실제 납부액과 비교한 현재 부족액이다. 주문이 아직 없어도 응답한다. */
        @com.fasterxml.jackson.annotation.JsonProperty("additionalPaymentAmount")
        public BigDecimal additionalPaymentAmount() { return contractAmount.subtract(paidAmount).max(BigDecimal.ZERO); }
        /** 추가 납부 의무와 신청 변경 성공을 결제 완료로 혼동하지 않는다. */
        @com.fasterxml.jackson.annotation.JsonProperty("additionalPaymentRequired")
        public boolean additionalPaymentRequired() { return additionalPaymentAmount().signum() > 0; }
        /** 준비 시점의 환불 예정액이다. 환불 완료 여부는 실행 결과로 확인한다. */
        @com.fasterxml.jackson.annotation.JsonProperty("refundAmount")
        public BigDecimal refundAmount() { return paidAmount.subtract(contractAmount).max(BigDecimal.ZERO); }
        /** 프론트가 금액 부호를 추론하지 않고 관리자에게 후속 조치를 알리도록 한다. */
        @com.fasterxml.jackson.annotation.JsonProperty("settlementType")
        public String settlementType() {
            return additionalPaymentRequired() ? "ADDITIONAL_PAYMENT_REQUIRED"
                    : refundAmount().signum() > 0 ? "REFUND" : "NO_PAYMENT_CHANGE";
        }
        /** 관리자가 직접 결제하지 않으므로 결제창 주문은 이 응답에서 생성하지 않는다. */
        @com.fasterxml.jackson.annotation.JsonProperty("paymentOrder")
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
        public Object paymentOrder() { return null; }
    }
    /** 원결제별 준비 시도 식별자다. 요청시각/완료시각은 04에서 기록한다. */
    public record Refund(String paymentCancelId, String paymentId, BigDecimal amount,
            PaymentCancelType type, PaymentCancelStatus status) { }
}
