package kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.dto.TossPaymentConfirmRequest;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.dto.TossPaymentConfirmResponse;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.dto.TossPaymentErrorResponse;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.exception.TossPaymentApiException;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.exception.TossPaymentTransportException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;



/**
 * Transactional을 지니면 안되는 계층. 토스 서버와 HTTP 통신을 진행하므로 요청 실패면 그냥 실패지, 롤백 대상이 아님
 */
@Component
public class TossPaymentClient {

    private final RestClient tossPaymentRestClient;

    private final ObjectMapper objectMapper;

    public TossPaymentClient(
            @Qualifier("tossPaymentRestClient")
            RestClient tossPaymentRestClient,
            ObjectMapper objectMapper
    ) {

        this.tossPaymentRestClient =
                tossPaymentRestClient;

        this.objectMapper =
                objectMapper;
    }


    public TossPaymentConfirmResponse confirm(
            TossPaymentConfirmRequest request,
            String idempotencyKey
    ) {

        try {

            return tossPaymentRestClient
                    .post()

                    .uri("/v1/payments/confirm")

                    /*
                     * Payment 생성 시 저장했던
                     * 동일 confirmIdempotencyKey를 사용한다.
                     */
                    .header(
                            "Idempotency-Key",
                            idempotencyKey
                    )

                    .body(request)

                    .retrieve()

                    .onStatus(
                            HttpStatusCode::isError,
                            (httpRequest, httpResponse) -> {

                                TossPaymentErrorResponse error;

                                try {

                                    error =
                                            objectMapper.readValue(
                                                    httpResponse.getBody(),
                                                    TossPaymentErrorResponse.class
                                            );

                                } catch (Exception parsingException) {

                                    throw new TossPaymentApiException(
                                            httpResponse
                                                    .getStatusCode()
                                                    .value(),
                                            "UNKNOWN_TOSS_ERROR",
                                            "Toss Payments 오류 응답을 해석하지 못했습니다."
                                    );
                                }


                                throw new TossPaymentApiException(
                                        httpResponse
                                                .getStatusCode()
                                                .value(),
                                        error.code(),
                                        error.message()
                                );
                            }
                    )

                    .body(
                            TossPaymentConfirmResponse.class
                    );

        } catch (TossPaymentApiException e) {

            /*
             * Toss가 명확한 HTTP 응답을 보내준 경우.
             *
             * Application Layer가 FAILED/UNKNOWN을
             * 최종 판단할 수 있도록 그대로 전달.
             */
            throw e;

        } catch (ResourceAccessException e) {

            /*
             * timeout / connection 문제.
             *
             * 실제 결제 처리 여부를 여기서 판단하면 안 된다.
             */
            throw new TossPaymentTransportException(e);
        }
    }
}