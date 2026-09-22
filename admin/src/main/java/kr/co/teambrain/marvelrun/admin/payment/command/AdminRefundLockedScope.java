package kr.co.teambrain.marvelrun.admin.payment.command;

import java.math.BigDecimal;
import java.util.List;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;

/** 같은 쓰기 트랜잭션 안에서만 유효한 잠금 범위다. 환불 가능 금액의 확정을 의미하지 않는다. */
public record AdminRefundLockedScope(String eventId, String organizationId,
        List<RegistrationRow> registrations, List<PaymentRow> payments, List<CancelRow> cancellations) {
    public AdminRefundLockedScope {
        registrations = List.copyOf(registrations);
        payments = List.copyOf(payments);
        cancellations = List.copyOf(cancellations);
    }
    /** 선택한 참가자의 현재 요약이다. 원귀속 대사는 다음 환불 준비 단계에서 수행한다. */
    public record RegistrationRow(String id, String organizationId, boolean deleted,
            RegistrationStatus status, BigDecimal contractAmount, BigDecimal paidAmount) { }
    /** READY도 충돌 검사 범위에 포함하지만 실제 환불 원결제로 사용하지 않는다. */
    public record PaymentRow(String id, PaymentProcessStatus status) { }
    /** 과거 성공/실패 기록도 유지하여 다음 단계의 귀속 대사에 연결한다. */
    public record CancelRow(String id, String paymentId, PaymentCancelStatus status) { }
}
