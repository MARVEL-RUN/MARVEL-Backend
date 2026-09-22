package kr.co.teambrain.marvelrun.admin.payment.command.evidence;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import kr.co.teambrain.marvelrun.admin.payment.command.infrastructure.toss.refund.TossCancelResponse;

/** 조회 증거와 금융 처리 결과를 분리하는 계약이다. */
public final class AdminRefundEvidenceModels {
    private AdminRefundEvidenceModels() { }
    /** 조회 결과는 재환불 허가나 금융 원장 변경을 의미하지 않는다. */
    public enum Verdict { EXTERNAL_CANCEL_CONFIRMED, NOT_IDENTIFIABLE, KNOWN_CANCEL_NOT_OBSERVED,
        RESPONSE_MISMATCH, LOOKUP_UNAVAILABLE, LOCAL_CHANGED }
    /** 서버 내부 비교 전용이며 REST 응답 또는 로그에 그대로 출력하지 않는다. */
    public record Snapshot(String paymentCancelId, String paymentId, String paymentKey, String orderId,
            BigDecimal totalAmount, BigDecimal cancelAmount, String localStatus, List<String> transactionKeys) { }
    /** 외부 원문 대신 제한된 Payment 객체만 메모리에서 사용한다. */
    public record Lookup(Integer httpStatus, String errorCode, TossCancelResponse payment) { }
    /** 외부 취소 이력의 제한된 증거. 거래 키 원문은 응답에 포함하지 않는다. */
    public record ObservedCancel(String transactionKeyHash, BigDecimal amount, String status, OffsetDateTime canceledAt) { }
    /** 키 대조 여부와 금액만 보존하며 카드·고객정보·결제 인증 키는 저장하지 않는다. */
    public record Observation(String paymentStatus, BigDecimal totalAmount, BigDecimal balanceAmount,
            boolean identityMatches, List<ObservedCancel> cancels) { }
    /** 확인 당시 서버 상태 스냅샷이다. 현재 최신 상태 또는 정합성 수정 완료를 뜻하지 않는다. */
    public record Local(String status, BigDecimal cancelAmount, int knownTransactionKeyCount, List<String> knownTransactionKeyHashes) { }
    /** 모든 판정에서 금융 상태 변경 및 재전송은 금지되어 있다. */
    public record Decision(Verdict verdict, String reason, Observation observation) { }
    /** 생성·조회 응답 공통 계약이다. 금융 원장과 별개로 시각·관리자·증거를 보존한다. */
    public record Evidence(String evidenceId, String eventId, String paymentCancelId, String paymentId,
            String checkedBy, LocalDateTime startedAt, LocalDateTime checkedAt, Integer httpStatus,
            String errorCode, Verdict verdict, String reason, Local before, Local after,
            Observation observation, boolean financialStateChanged, boolean retryAllowed) { }
    /** 저장 기록 조회는 개수와 페이지를 제한하며 외부 GET을 다시 호출하지 않는다. */
    public record Page(int page, int size, List<Evidence> items) { }
}
