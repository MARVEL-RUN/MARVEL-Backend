package kr.co.teambrain.marvelrun.admin.payment.command.infrastructure.toss.refund;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 과거 취소가 섞인 응답에서 이번 취소만 인정하는 계약을 검증한다. */
class TossCancelResponseVerifierTest {
    private final TossCancelResponseVerifier verifier = new TossCancelResponseVerifier();
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-20T12:00:00+09:00");

    /** 과거 성공 취소 금액을 다시 환불 적용 대상으로 반환하지 않는다. */
    @Test
    void selectsOnlyNewCancellationRegardlessOfArrayOrder() {
        TossCancelResponse response = response("key", "order", "PARTIAL_CANCELED", "new", "20000",
                List.of(cancel("new", "10000", "20000"), cancel("old", "10000", "30000")));
        VerifiedTossCancellation result = verifier.verify(attempt(), response).orElseThrow();
        assertThat(result.transactionKey()).isEqualTo("new");
        assertThat(result.cancelAmount()).isEqualByComparingTo("10000");
        assertThat(result.canceledAt()).isEqualTo(NOW);
    }

    /** 마지막 잔액 전체를 환불한 경우 CANCELED 상태와 잔액 0을 요구한다. */
    @Test
    void acceptsFullRemainingRefund() {
        TossCancelAttempt attempt = new TossCancelAttempt("cancel", "key", "order", "idempotency",
                "신청 수정", new BigDecimal("40000"), new BigDecimal("30000"),
                Map.of("old", new BigDecimal("10000")));
        assertThat(verifier.verify(attempt, response("key", "order", "CANCELED", "new", "0",
                List.of(cancel("old", "10000", "30000"), cancel("new", "30000", "0"))))).isPresent();
    }

    /** 금액·식별자·이력·상태 중 어느 하나라도 맞지 않으면 성공으로 확정하지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"payment", "order", "amount", "balance", "history", "duplicate",
            "missing", "multiple", "lastKey", "status", "pending", "time", "currency", "method", "total"})
    void rejectsAmbiguousOrMismatchedResponses(String variant) {
        TossCancelResponse.Cancel old = cancel("old", "10000", "30000");
        TossCancelResponse.Cancel current = cancel("new", "10000", "20000");
        List<TossCancelResponse.Cancel> entries = switch (variant) {
            case "amount" -> List.of(old, cancel("new", "9000", "20000"));
            case "history" -> List.of(cancel("old", "9000", "31000"), current);
            case "duplicate" -> List.of(old, old);
            case "missing" -> List.of(current);
            case "multiple" -> List.of(old, current, cancel("other", "1000", "19000"));
            case "pending" -> List.of(old, new TossCancelResponse.Cancel("new",
                    new BigDecimal("10000"), new BigDecimal("20000"), "PROCESSING", NOW));
            case "time" -> List.of(old, new TossCancelResponse.Cancel("new",
                    new BigDecimal("10000"), new BigDecimal("20000"), "DONE", null));
            default -> List.of(old, current);
        };
        TossCancelResponse response = new TossCancelResponse(
                variant.equals("payment") ? "other" : "key",
                variant.equals("order") ? "other" : "order",
                variant.equals("currency") ? "USD" : "KRW",
                variant.equals("method") ? "가상계좌" : "카드",
                variant.equals("status") ? "DONE" : "PARTIAL_CANCELED",
                new BigDecimal(variant.equals("total") ? "50000" : "40000"),
                new BigDecimal(variant.equals("balance") ? "21000" : "20000"),
                variant.equals("lastKey") ? "old" : "new", entries);
        assertThat(verifier.verify(attempt(), response)).isEmpty();
    }

    /** 응답 자체가 없는 경우 성공으로 확정하지 않는다. */
    @Test
    void rejectsMissingBody() {
        assertThat(verifier.verify(attempt(), null)).isEmpty();
    }

    /** 요청 정보가 구성된 뒤 원본 Map이 바뀌어도 검증 기준이 바뀌지 않는다. */
    @Test
    void copiesHistoryAtConstruction() {
        Map<String, BigDecimal> history = new HashMap<>();
        history.put("old", new BigDecimal("10000"));
        TossCancelAttempt attempt = new TossCancelAttempt("cancel", "key", "order", "idem", "수정",
                new BigDecimal("40000"), new BigDecimal("10000"), history);
        history.clear();
        assertThat(attempt.completedCancels()).containsKey("old");
    }

    /** 음수·0·원화 소수 금액·초과 환불은 외부 요청 정보로 만들 수 없다. */
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.5", "30001"})
    void rejectsInvalidRequestedAmount(String amount) {
        assertThatThrownBy(() -> new TossCancelAttempt("cancel", "key", "order", "idem", "수정",
                new BigDecimal("40000"), new BigDecimal(amount),
                Map.of("old", new BigDecimal("10000"))))
                .isInstanceOf(CustomException.class);
    }

    /** 기존 성공 취소 1만 원이 있는 원결제 4만 원의 요청을 구성한다. */
    private static TossCancelAttempt attempt() {
        return new TossCancelAttempt("cancel", "key", "order", "idempotency", "신청 수정",
                new BigDecimal("40000"), new BigDecimal("10000"),
                Map.of("old", new BigDecimal("10000")));
    }

    /** 성공 취소 이력 한 건을 구성한다. */
    private static TossCancelResponse.Cancel cancel(String key, String amount, String balance) {
        return new TossCancelResponse.Cancel(key, new BigDecimal(amount), new BigDecimal(balance), "DONE", NOW);
    }

    /** 기본 카드 결제 취소 응답을 구성한다. */
    private static TossCancelResponse response(String key, String order, String status, String last,
                                               String balance, List<TossCancelResponse.Cancel> entries) {
        return new TossCancelResponse(key, order, "KRW", "카드", status,
                new BigDecimal("40000"), new BigDecimal(balance), last, entries);
    }
}