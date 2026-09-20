package kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.refund;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 실제 Toss와 DB에 접근하지 않고 HTTP 요청 및 결과 판정 계약을 검증한다. */
class TossPaymentCancelClientTest {
    private MockRestServiceServer server;
    private TossPaymentCancelClient client;

    /** 모든 HTTP 요청을 메모리의 테스트 서버로 가로챈다. */
    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://refund.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TossPaymentCancelClient(builder.build(), new ObjectMapper().findAndRegisterModules(),
                new TossCancelResponseVerifier());
    }

    /** 저장된 멱등키와 명시적 금액을 사용하고 검증된 취소 증거를 반환한다. */
    @Test
    void sendsExactRequestAndReturnsVerifiedEvidence() {
        server.expect(requestTo("https://refund.test/v1/payments/key/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "saved-key"))
                .andExpect(content().json("{\"cancelReason\":\"신청 수정\",\"cancelAmount\":10000}"))
                .andRespond(withSuccess(successBody(), MediaType.APPLICATION_JSON));
        TossCancelOutcome outcome = client.cancel(attempt());
        assertThat(outcome.kind()).isEqualTo(TossCancelOutcome.Kind.VERIFIED);
        assertThat(outcome.cancellation().transactionKey()).isEqualTo("new");
        server.verify();
    }

    /** 일부 명시적인 거절만 실패로 분류한다. */
    @ParameterizedTest
    @CsvSource({"401,UNAUTHORIZED_KEY", "400,EXCEED_CANCEL_AMOUNT_DISCOUNT_AMOUNT",
            "403,EXCEED_MAX_REFUND_DUE"})
    void classifiesKnownRejection(int status, String code) {
        expectError(status, code);
        assertThat(client.cancel(attempt()).kind()).isEqualTo(TossCancelOutcome.Kind.REJECTED);
        server.verify();
    }

    /** 이미 취소·잔액 불일치·미확인 오류·서버 오류는 실패로 단정하지 않는다. */
    @ParameterizedTest
    @CsvSource({"400,ALREADY_CANCELED_PAYMENT", "400,ALREADY_REFUND_PAYMENT",
            "403,NOT_CANCELABLE_AMOUNT", "500,INTERNAL_SERVER_ERROR", "400,UNRECOGNIZED_CODE"})
    void classifiesAmbiguousErrorAsUnknown(int status, String code) {
        expectError(status, code);
        assertThat(client.cancel(attempt()).kind()).isEqualTo(TossCancelOutcome.Kind.UNKNOWN);
        server.verify();
    }

    /** 응답을 읽지 못했을 때 재전송하지 않고 결과불명을 반환한다. */
    @Test
    void doesNotRetryOnConnectionFailure() {
        server.expect(requestTo("https://refund.test/v1/payments/key/cancel"))
                .andRespond(withException(new IOException("connection lost")));
        assertThat(client.cancel(attempt()).kind()).isEqualTo(TossCancelOutcome.Kind.UNKNOWN);
        server.verify();
    }

    /** HTTP 성공이라도 본문이 없거나 해석할 수 없으면 환불 성공이 아니다. */
    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "not-json"})
    void rejectsMalformedSuccessBody(String body) {
        server.expect(requestTo("https://refund.test/v1/payments/key/cancel"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThat(client.cancel(attempt()).kind()).isEqualTo(TossCancelOutcome.Kind.UNKNOWN);
        server.verify();
    }

    /** 실패 본문의 원문을 외부 응답이나 로그용 메시지로 전달하지 않는다. */
    @Test
    void malformedErrorBodyIsUnknown() {
        server.expect(requestTo("https://refund.test/v1/payments/key/cancel"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("not-json"));
        assertThat(client.cancel(attempt()).kind()).isEqualTo(TossCancelOutcome.Kind.UNKNOWN);
        server.verify();
    }

    /** 트랜잭션 안에서 호출하면 네트워크 요청 전에 차단하고 스레드 상태를 복원한다. */
    @Test
    void blocksHttpInsideTransaction() {
        boolean previous = TransactionSynchronizationManager.isActualTransactionActive();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> client.cancel(attempt()))
                    .isInstanceOfSatisfying(CustomException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_CANCEL_CONFLICT));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(previous);
        }
        server.verify();
    }

    /** HTTP 오류 한 건만 허용하여 숨은 자동 재시도를 검출한다. */
    private void expectError(int status, String code) {
        server.expect(requestTo("https://refund.test/v1/payments/key/cancel"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"" + code + "\",\"message\":\"test\"}"));
    }

    /** 저장된 환불 시도에서 가져온 최소 요청을 구성한다. */
    private static TossCancelAttempt attempt() {
        return new TossCancelAttempt("cancel-id", "key", "order", "saved-key", "신청 수정",
                new BigDecimal("40000"), new BigDecimal("10000"), Map.of());
    }

    /** 알려지지 않은 추가 필드는 무시하되 취소 검증에 필요한 값은 제공한다. */
    private static String successBody() {
        return """
                {"paymentKey":"key","orderId":"order","currency":"KRW","method":"카드",
                 "status":"PARTIAL_CANCELED","totalAmount":40000,"balanceAmount":30000,
                 "lastTransactionKey":"new","extraField":"ignored",
                 "cancels":[{"transactionKey":"new","cancelAmount":10000,"refundableAmount":30000,
                             "cancelStatus":"DONE","canceledAt":"2026-09-20T12:00:00+09:00"}]}
                """;
    }
}