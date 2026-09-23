package kr.co.teambrain.marvelrun.user.payment.command.application;
import java.util.LinkedHashMap;
import java.util.Map;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.refund.TossCancelOutcome;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.refund.VerifiedTossCancellation;
/** 기존 JSON metadata에 외부 증거와 로컬 반영 비교를 추가한다. 금융 상태는 변경하지 않는다. */
public final class PaymentResultLogMetadata {
    /** 정적 로그 구성 도구다. */
    private PaymentResultLogMetadata() { }
    /** 모르는 결과를 실패나 성공으로 추정하지 않는다. */
    public static PaymentResultComparison classify(Boolean external, Boolean local) {
        if (external == null || local == null) { return PaymentResultComparison.UNVERIFIED; }
        if (!external.equals(local)) { return PaymentResultComparison.MISMATCH; }
        return external ? PaymentResultComparison.SUCCESS : PaymentResultComparison.FAILED;
    }
    /** 기존 metadata 필드는 유지하고 버전이 있는 비교 객체만 추가한다. */
    public static Map<String, Object> append(Map<String, Object> existing, String operation,
            Boolean external, Boolean local, String localState, String source,
            String externalState, Map<String, Object> evidence) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (existing != null) { result.putAll(existing); }
        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("schemaVersion", 1);
        comparison.put("status", classify(external, local).name());
        comparison.put("operation", operation);
        comparison.put("externalTargetReached", external);
        comparison.put("localTargetReached", local);
        comparison.put("localState", localState);
        comparison.put("evidenceSource", source);
        comparison.put("externalState", externalState);
        comparison.put("evidence", evidence == null ? Map.of() : new LinkedHashMap<>(evidence));
        result.put("resultComparison", comparison);
        return result;
    }
    /** 결과불명 저장에 전달된 검증 성공 증거도 잃지 않고 비교한다. */
    public static Map<String, Object> refund(Map<String, Object> existing, PaymentCancelStatus state, TossCancelOutcome outcome) {
        VerifiedTossCancellation verified = outcome == null ? null : outcome.cancellation();
        Boolean external = verified != null ? Boolean.TRUE
                : outcome != null && outcome.kind() == TossCancelOutcome.Kind.REJECTED ? Boolean.FALSE : null;
        Boolean local = state == PaymentCancelStatus.DONE ? Boolean.TRUE
                : state == PaymentCancelStatus.FAILED || state == PaymentCancelStatus.UNKNOWN ? Boolean.FALSE : null;
        Map<String, Object> evidence = new LinkedHashMap<>();
        if (verified != null) {
            evidence.put("cancelAmount", verified.cancelAmount());
            evidence.put("refundableAmount", verified.refundableAmount());
            evidence.put("canceledAt", verified.canceledAt().toString());
        }
        if (outcome != null) {
            evidence.put("httpStatus", outcome.httpStatus());
            evidence.put("errorCode", outcome.errorCode());
        }
        return append(existing, "CANCEL", external, local, state == null ? null : state.name(),
                verified != null ? "CANCEL_RESPONSE" : Boolean.FALSE.equals(external) ? "ERROR_RESPONSE" : "UNAVAILABLE",
                verified == null ? null : verified.paymentStatus(), evidence);
    }
    /** 과거·알 수 없는 버전·손상된 비교 정보는 미확인으로 표시한다. 원본 metadata는 변경하지 않는다. */
    public static PaymentResultComparison readStatus(Map<String, Object> metadata) {
        if (metadata == null || !(metadata.get("resultComparison") instanceof Map<?, ?> comparison)
                || !(comparison.get("schemaVersion") instanceof Number version) || version.doubleValue() != 1.0
                || !(comparison.get("externalTargetReached") instanceof Boolean external)
                || !(comparison.get("localTargetReached") instanceof Boolean local)) {
            return PaymentResultComparison.UNVERIFIED;
        }
        PaymentResultComparison actual = classify(external, local);
        return actual.name().equals(comparison.get("status")) ? actual : PaymentResultComparison.UNVERIFIED;
    }
}
