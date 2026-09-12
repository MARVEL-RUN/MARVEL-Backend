package kr.co.teambrain.marvelrun.user.payment.command.application;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentConfirmContext;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentConfirmRequest;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentConfirmResponse;
import kr.co.teambrain.marvelrun.user.payment.command.application.exception.InvalidTossSuccessResponseException;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.client.TossPaymentClient;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.dto.TossPaymentConfirmRequest;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.dto.TossPaymentConfirmResponse;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.exception.TossPaymentApiException;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.exception.TossPaymentTransportException;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentConfirmService {

    private final PaymentConfirmTransactionService
            paymentConfirmTransactionService;

    private final TossPaymentClient
            tossPaymentClient;


    public PaymentConfirmResponse confirm(
            PaymentConfirmRequest request
    ) {

        String correlationId =
                UUID.randomUUID().toString();


        /*
         * ============================
         * Tx 1
         * ============================
         */
        PaymentConfirmContext context;

        try {

            context =
                    paymentConfirmTransactionService
                            .beginConfirm(
                                    request,
                                    correlationId
                            );

        } catch (
                ObjectOptimisticLockingFailureException e
        ) {

            /*
             * 같은 Payment에 confirm 요청이 동시에 들어온 경우.
             *
             * @Version 충돌.
             */
            throw new CustomException(
                    ErrorCode.PAYMENT_NOT_CONFIRMABLE
            );
        }


        /*
         * ============================
         * Transaction 없음
         *
         * Toss HTTP
         * ============================
         */
        TossPaymentConfirmResponse tossResponse;

        try {

            TossPaymentConfirmRequest tossRequest =
                    new TossPaymentConfirmRequest(
                            context.paymentKey(),
                            context.orderId(),
                            context.amount()
                    );


            tossResponse =
                    tossPaymentClient.confirm(
                            tossRequest,
                            context.idempotencyKey()
                    );


        } catch (
                TossPaymentTransportException e
        ) {

            /*
             * 응답 자체를 받지 못함.
             *
             * Toss가 결제를 처리했는지 모른다.
             */
            paymentConfirmTransactionService
                    .markConfirmUnknown(
                            context,
                            "TOSS_CONFIRM_TRANSPORT_ERROR",
                            "Toss confirm 응답을 확인하지 못했습니다."
                    );


            throw new CustomException(
                    ErrorCode.PAYMENT_CONFIRM_UNKNOWN
            );


        } catch (
                TossPaymentApiException e
        ) {

            /*
             * ALREADY_PROCESSED_PAYMENT:
             *
             * '실패'라고 단정하면 안 됨.
             * 이미 어떤 처리가 발생했다는 의미이므로
             * 실제 Toss Payment 상태 확인이 필요하다.
             */
            if ("ALREADY_PROCESSED_PAYMENT"
                    .equals(
                            e.getTossErrorCode()
                    )) {

                paymentConfirmTransactionService
                        .markConfirmUnknown(
                                context,
                                e.getTossErrorCode(),
                                e.getTossErrorMessage()
                        );


                throw new CustomException(
                        ErrorCode.PAYMENT_CONFIRM_UNKNOWN
                );
            }


            /*
             * Toss가 명시적인 실패 응답을 반환한 경우.
             */
            paymentConfirmTransactionService
                    .failConfirm(
                            context,
                            e
                    );


            throw new CustomException(
                    ErrorCode.PAYMENT_CONFIRM_FAILED
            );
        }


        /*
         * ============================
         * Tx 2
         * ============================
         */
        try {

            return paymentConfirmTransactionService
                    .completeConfirm(
                            context,
                            tossResponse
                    );

        } catch (
                InvalidTossSuccessResponseException e
        ) {

            /*
             * 매우 중요.
             *
             * Toss HTTP 200 이후 검증 실패이므로
             * 금융 승인이 발생했을 수 있다.
             *
             * FAILED로 저장하면 안 된다.
             */
            paymentConfirmTransactionService
                    .markConfirmUnknown(
                            context,
                            "INVALID_TOSS_SUCCESS_RESPONSE",
                            "Toss 성공 응답과 저장된 결제 정보가 일치하지 않습니다."
                    );


            throw new CustomException(
                    ErrorCode.PAYMENT_CONFIRM_UNKNOWN
            );
        }
    }
}