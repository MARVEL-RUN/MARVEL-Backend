package kr.co.teambrain.marvelrun.admin.payment.command.batch;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import kr.co.teambrain.marvelrun.admin.payment.command.dto.AdminPaymentPartialRefundTarget;

/** 배치 접수·대상 결과 계약이다. 금융 Entity의 상태를 대체하지 않는다. */
public final class AdminRefundBatchModels {
    private AdminRefundBatchModels() { }
    /** 서로 다른 환불 명령을 같은 요청 식별자로 혼용하지 않는다. */
    public enum Operation { FULL, PARTIAL }
    /** 접수 시 대상과 버전을 고정한다. 오류 대상도 목록에 남긴다. */
    public record Target(String registrationId, String organizationId, Long version,
            AdminPaymentPartialRefundTarget change, String errorCode, List<String> activeOrganizationMemberIds) {
        /** 기존 저장 JSON과 단건·정보 변경 호출의 생성자 호환성을 유지한다. */
        public Target(String registrationId, String organizationId, Long version,
                AdminPaymentPartialRefundTarget change, String errorCode) {
            this(registrationId, organizationId, version, change, errorCode, null);
        }
    }
    /** 외부 결과 항목은 신청자별로 유지하면서 내부 실행 소유권만 함께 확보한다. */
    public record WorkItem(int itemNo, Target target) { }
    /** 작업 소유권은 DB에서 먼저 커밋한 후 업무 트랜잭션을 시작한다. */
    public record Work(String batchId, int itemNo, String eventId, String adminId, String requestId,
            String reason, Operation operation, Target target, List<WorkItem> executionItems) {
        /** 기존 단일 항목 호출은 한 명짜리 실행 묶음으로 해석한다. */
        public Work(String batchId, int itemNo, String eventId, String adminId, String requestId,
                String reason, Operation operation, Target target) {
            this(batchId, itemNo, eventId, adminId, requestId, reason, operation, target,
                    List.of(new WorkItem(itemNo, target)));
        }
    }
    /** 완료는 배치 실행 종료이며 모든 환불의 성공을 뜻하지 않는다. */
    public record Summary(String batchId, String requestId, String operation, String status,
            LocalDateTime acceptedAt, LocalDateTime updatedAt, LocalDateTime completedAt,
            int total, Map<String, Long> counts) { }
    /** 준비 스냅샷을 완료 후 잔액으로 표시하지 않는다. 실제 잔액은 기존 결제 조회를 사용한다. */
    public record Item(int itemNo, String registrationId, String organizationId, String status,
            String errorCode, LocalDateTime startedAt, LocalDateTime finishedAt,
            JsonNode preparation, JsonNode result) {
        /** 업무 오류의 상세 원인도 결과 조회/응답에 표시한다. */
        @com.fasterxml.jackson.annotation.JsonProperty("message")
        public String message() {
            if (result != null && result.hasNonNull("message")) { return result.get("message").asText(); }
            if (errorCode == null) { return null; }
            try { return kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode.valueOf(errorCode).getMessage(); }
            catch (IllegalArgumentException error) { return "처리 결과 확인이 필요합니다. (" + errorCode + ")"; }
        }
    }
    /** 신규 접수는 전체 대상 결과를 반환하며, 과거 대량 기록은 잘림 여부를 표시한다. */
    public record Response(Summary summary, List<Item> items, boolean resultsTruncated) { }
    /** 대상 조회는 페이지 크기를 제한한다. */
    public record Items(int page, int size, long total, List<Item> items) { }
}
