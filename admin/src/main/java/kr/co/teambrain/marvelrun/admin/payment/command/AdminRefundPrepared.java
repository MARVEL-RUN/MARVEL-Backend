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
            BigDecimal paidAmount, RegistrationStatus status, ReservationStatus reservationStatus, boolean participationCanceled) { }
    /** 원결제별 준비 시도 식별자다. 요청시각/완료시각은 04에서 기록한다. */
    public record Refund(String paymentCancelId, String paymentId, BigDecimal amount,
            PaymentCancelType type, PaymentCancelStatus status) { }
}
