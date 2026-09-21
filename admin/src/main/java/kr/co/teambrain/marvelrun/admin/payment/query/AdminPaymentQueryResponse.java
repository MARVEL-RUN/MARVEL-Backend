package kr.co.teambrain.marvelrun.admin.payment.query;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 관리자 조회 전용 DTO 묶음이다. 엔티티와 비밀번호·결제 인증 키를 반환하지 않는다. */
public final class AdminPaymentQueryResponse {
    /** DTO 이름 공간으로만 사용한다. */
    private AdminPaymentQueryResponse() { }

    /** 0부터 시작하는 페이지와 전체 건수이다. */
    public record Page<T>(List<T> content, int page, int size, long totalElements, long totalPages) { }

    /** Organization에 저장된 대표자 정보이며 참가자에서 추정하지 않는다. */
    public record Leader(String name, String birth, String phNum) { }

    /** 개인 또는 단체 대상과 현재 신청 금액을 금융 이력 상단에 제공한다. */
    public record Finance(String registrationId, String organizationId, String name,
            Leader leader, BigDecimal contractAmount, String registrationStatus,
            String paymentStatus, Page<Payment> payments) { }

    /** 주문 전체 금액과 각 참가자 귀속·취소를 구분한다. 실패·무효 주문도 포함한다. */
    public record Payment(String paymentId, String orderId, String orderName,
            BigDecimal amount, String purpose, String paymentStatus, String tossStatus,
            String paymentMethod, String easyPayProvider, LocalDateTime createdAt,
            LocalDateTime approvedAt, boolean allocationMissing,
            List<Allocation> allocations, List<Cancel> cancels) { }

    /** 현재 명단에서 제외되거나 참가자 정보가 없어도 귀속 금액을 보존해 표시한다. */
    public record Allocation(String paymentAllocationId, String registrationId, String name,
            BigDecimal allocatedAmount, String allocationPurpose,
            boolean excludedFromCurrentRoster, boolean registrationMissing) { }

    /** 원결제의 취소 시도이다. 취소 귀속이 없어도 이 행 자체를 반환한다. */
    public record Cancel(String paymentCancelId, String cancelType, String purpose,
            BigDecimal cancelAmount, String cancelReason, String status,
            LocalDateTime createdAt, LocalDateTime requestedAt, LocalDateTime canceledAt,
            String errorCode, String errorMessage, BigDecimal refundableAmountAfterCancel,
            boolean allocationMissing, List<CancelAllocation> allocations) { }

    /** 취소 금액이 어느 원결제 귀속의 참가자에게 배분됐는지 표시한다. */
    public record CancelAllocation(String paymentCancelAllocationId, String paymentAllocationId,
            String registrationId, String name, BigDecimal allocatedAmount,
            boolean excludedFromCurrentRoster, boolean registrationMissing,
            boolean originalAllocationMissing, boolean originalPaymentMismatch) { }

    /** 운영용 로그이며 내부 ID·인증 키·추적 키는 반환하지 않는다. */
    public record Log(LocalDateTime createdAt, String orderId, String processType, String source,
            Integer httpStatus, String errorCode, String errorMessage, Map<String, Object> metadata) { }
}
