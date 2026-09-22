package kr.co.teambrain.marvelrun.admin.payment.command.infrastructure.toss.refund;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.payment.command.infrastructure.toss.dto.TossPaymentErrorResponse;
import kr.co.teambrain.marvelrun.admin.payment.command.infrastructure.toss.exception.TossPaymentApiException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 기존 Toss 인증과 시간 제한 설정을 재사용하여 취소를 한 번만 요청한다. */
@Component
public class TossPaymentCancelClient {
    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final TossCancelResponseVerifier verifier;

    /** 승인 담당 코드와 분리하되 동일한 연결 설정을 주입받는다. */
    public TossPaymentCancelClient(
            @Qualifier("tossPaymentRestClient") RestClient client,
            ObjectMapper objectMapper, TossCancelResponseVerifier verifier) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.verifier = verifier;
    }

    /** 커밋된 시도를 외부에 전송한다. DB 트랜잭션 안에서 호출하면 전송 전에 차단한다. */
    public TossCancelOutcome cancel(TossCancelAttempt attempt) {
        if (attempt == null) {
            throw new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new CustomException(ErrorCode.PAYMENT_CANCEL_CONFLICT);
        }
        try {
            TossCancelResponse response = client.post()
                    .uri("/v1/payments/{paymentKey}/cancel", attempt.paymentKey())
                    .header("Idempotency-Key", attempt.idempotencyKey())
                    .body(new TossCancelRequest(attempt.cancelReason(), attempt.cancelAmount()))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, httpResponse) -> {
                        TossPaymentErrorResponse error;
                        try {
                            error = objectMapper.readValue(httpResponse.getBody(),
                                    TossPaymentErrorResponse.class);
                        } catch (Exception parseFailure) {
                            throw new TossPaymentApiException(httpResponse.getStatusCode().value(),
                                    "UNKNOWN_TOSS_ERROR", "취소 오류 응답을 해석하지 못했습니다.");
                        }
                        if (error == null || error.code() == null) {
                            throw new TossPaymentApiException(httpResponse.getStatusCode().value(),
                                    "UNKNOWN_TOSS_ERROR", "취소 오류 코드가 없습니다.");
                        }
                        throw new TossPaymentApiException(httpResponse.getStatusCode().value(),
                                error.code(), "취소 요청이 정상 응답을 반환하지 않았습니다.");
                    })
                    .body(TossCancelResponse.class);
            Optional<VerifiedTossCancellation> verified = verifier.verify(attempt, response);
            return verified.map(TossCancelOutcome::verified).orElseGet(
                    () -> TossCancelOutcome.unknown(200, "CANCEL_RESPONSE_MISMATCH"));
        } catch (TossPaymentApiException error) {
            if (definiteRejection(error)) {
                return TossCancelOutcome.rejected(error.getHttpStatus(), error.getTossErrorCode());
            }
            return TossCancelOutcome.unknown(error.getHttpStatus(), "TOSS_CANCEL_UNCONFIRMED");
        } catch (RestClientException error) {
            // 통신 단절 및 성공 응답 파싱 실패도 외부 취소가 없었다는 증거는 아니다.
            return TossCancelOutcome.unknown(null, "TOSS_CANCEL_RESPONSE_UNAVAILABLE");
        }
    }

    /** 인증·할인금액·환불기한의 명시적 거절만 실패로 분류하고 나머지는 보수적으로 보류한다. */
    private static boolean definiteRejection(TossPaymentApiException error) {
        return (error.getHttpStatus() == 401 && "UNAUTHORIZED_KEY".equals(error.getTossErrorCode()))
                || (error.getHttpStatus() == 400
                && "EXCEED_CANCEL_AMOUNT_DISCOUNT_AMOUNT".equals(error.getTossErrorCode()))
                || (error.getHttpStatus() == 403
                && "EXCEED_MAX_REFUND_DUE".equals(error.getTossErrorCode()));
    }
}
