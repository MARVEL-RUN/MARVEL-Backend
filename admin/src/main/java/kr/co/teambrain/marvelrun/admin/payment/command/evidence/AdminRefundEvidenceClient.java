package kr.co.teambrain.marvelrun.admin.payment.command.evidence;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import kr.co.teambrain.marvelrun.admin.payment.command.infrastructure.toss.refund.TossCancelResponse;
import kr.co.teambrain.marvelrun.admin.payment.command.evidence.AdminRefundEvidenceModels.Lookup;

/** 기존 Toss 인증·타임아웃을 재사용하는 GET 전용 클라이언트다. */
@Component
public class AdminRefundEvidenceClient {
    private final RestClient client;
    /** 기존 취소 클라이언트와 같은 RestClient 빈을 사용한다. */
    public AdminRefundEvidenceClient(@Qualifier("tossPaymentRestClient") RestClient client) { this.client=client; }

    /** 자동 재시도하지 않고 조회 1회만 수행한다. 외부 오류 원문은 저장/출력하지 않는다. */
    public Lookup lookup(String paymentKey) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("토스 증거 조회는 DB 트랜잭션 밖에서 실행해야 합니다.");
        }
        if (paymentKey == null || paymentKey.isBlank()) { return new Lookup(null,"PAYMENT_KEY_MISSING",null); }
        try {
            TossCancelResponse payment=client.get().uri("/v1/payments/{paymentKey}",paymentKey)
                    .retrieve().body(TossCancelResponse.class);
            return new Lookup(200,payment == null ? "EMPTY_RESPONSE" : null,payment);
        } catch (RestClientResponseException error) {
            return new Lookup(error.getStatusCode().value(),"TOSS_LOOKUP_HTTP_ERROR",null);
        } catch (RestClientException error) {
            return new Lookup(null,"TOSS_LOOKUP_UNAVAILABLE",null);
        }
    }
}
