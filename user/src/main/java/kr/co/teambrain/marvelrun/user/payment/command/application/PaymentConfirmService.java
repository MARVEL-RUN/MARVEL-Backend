package kr.co.teambrain.marvelrun.user.payment.command.application;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentConfirmContext;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentConfirmRequest;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentConfirmResponse;
import kr.co.teambrain.marvelrun.user.payment.command.application.exception.InvalidTossSuccessResponseException;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.TossConfirmFailureClassifier;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.client.TossPaymentClient;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.dto.TossPaymentConfirmRequest;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.dto.TossPaymentConfirmResponse;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.exception.TossPaymentApiException;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.exception.TossPaymentTransportException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;

import java.time.LocalDateTime;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentConfirmService {

    private final ServerTimeProvider serverTimeProvider;

    private final TossConfirmFailureClassifier tossConfirmFailureClassifier;

    private final PaymentConfirmTransactionService
            paymentConfirmTransactionService;

    private final TossPaymentClient
            tossPaymentClient;


    public PaymentConfirmResponse confirm(
            PaymentConfirmRequest request
    ) {
        LocalDateTime now =
                serverTimeProvider.currentDateTime();

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
                                    correlationId,
                                    now
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
         *  트랜잭션 밖에서 Toss 승인 호출
         * Toss HTTP
         * ============================
         *
         * Tx1에서 Payment와 Reservation의 처리 중 상태를 커밋한 뒤 호출한다.
         * 외부 응답을 기다리는 동안 DB 잠금을 유지하지 않는다.
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

        } catch (TossPaymentTransportException exception) {

            /*
             * 응답을 받지 못했다고 해서 승인 실패로 판단하지 않는다.
             * 예약과 홀딩은 유지하고 결제 결과 확인 대상으로 남긴다.
             */
            markUnknownSafely(
                    context,
                    "TOSS_CONFIRM_TRANSPORT_ERROR",
                    "Toss 승인 응답을 확인하지 못했습니다."
            );

            throw new CustomException(
                    ErrorCode.PAYMENT_CONFIRM_UNKNOWN,
                    exception
            );

        } catch (TossPaymentApiException exception) {

            /*
             * 이미 처리된 결제, 처리 중인 요청, 일시적 오류,
             * 그 밖의 미확인 응답은 확정 실패로 처리하지 않는다.
             */
            if (!tossConfirmFailureClassifier.isDefiniteFailure(exception)) {

                markUnknownSafely(
                        context,
                        exception.getTossErrorCode(),
                        exception.getTossErrorMessage()
                );

                throw new CustomException(
                        ErrorCode.PAYMENT_CONFIRM_UNKNOWN,
                        exception
                );
            }

            /*
             * 명확한 거절 응답인 경우에만 Payment를 FAILED로 변경하고
             * Reservation을 HELD로 복원한다.
             *
             * 이 로컬 반영에 실패하면 정상적으로 실패 처리를 마친 것이 아니므로
             * 결과 확인 대상으로 남긴다.
             */
            try {

                paymentConfirmTransactionService.failConfirm(
                        context,
                        exception
                );

            } catch (RuntimeException recordingException) {

                log.error(
                        "결제 실패 결과의 DB 반영 실패. paymentId={}, correlationId={}",
                        context.paymentId(),
                        context.correlationId(),
                        recordingException
                );

                markUnknownSafely(
                        context,
                        "LOCAL_CONFIRM_FAILURE_COMMIT_FAILED",
                        "결제 실패 결과의 로컬 반영을 완료하지 못했습니다."
                );

                throw new CustomException(
                        ErrorCode.PAYMENT_CONFIRM_UNKNOWN,
                        recordingException
                );
            }

            throw new CustomException(
                    ErrorCode.PAYMENT_CONFIRM_FAILED,
                    exception
            );

        } catch (RuntimeException exception) {

            /*
             * 응답 역직렬화 오류 등 예상하지 못한 클라이언트 오류도
             * 외부 승인 여부를 확인하지 못한 상황으로 처리한다.
             */
            log.error(
                    "Toss 승인 응답 처리 실패. paymentId={}, correlationId={}",
                    context.paymentId(),
                    context.correlationId(),
                    exception
            );

            markUnknownSafely(
                    context,
                    "TOSS_CONFIRM_RESPONSE_PROCESSING_ERROR",
                    "Toss 승인 응답 처리를 완료하지 못했습니다."
            );

            throw new CustomException(
                    ErrorCode.PAYMENT_CONFIRM_UNKNOWN,
                    exception
            );
        }


        /*
         * ============================
         * Tx2: 로컬 승인 확정
         * ============================
         *
         * 외부 Toss 호출은 이미 끝난 상태다.
         * 새로운 DB 트랜잭션에서 결제와 예약·정원 수량을 함께 확정한다.
         */
        try {

            return paymentConfirmTransactionService.completeConfirm(
                    context,
                    tossResponse,
                    serverTimeProvider.currentDateTime()
            );

        } catch (RuntimeException exception) {

            /*
             * 외부 승인 성공 이후 로컬 반영에 실패했으므로
             * 금융 승인 실패로 단정하지 않는다.
             */
            log.error(
                    "결제 승인 후 DB 반영 실패. paymentId={}, correlationId={}",
                    context.paymentId(),
                    context.correlationId(),
                    exception
            );

            markUnknownSafely(
                    context,
                    "LOCAL_CONFIRM_COMMIT_FAILED",
                    "결제 승인 이후 로컬 확정 처리를 완료하지 못했습니다."
            );

            throw new CustomException(
                    ErrorCode.PAYMENT_CONFIRM_UNKNOWN,
                    exception
            );
        }
    }

    /**
     * 별도 트랜잭션으로 결제 UNKNOWN 기록을 시도한다.
     *
     * DB 장애로 기록하지 못하더라도 예약 수량을 반환하지 않는다.
     * 실패 로그에는 결제와 상관관계 식별자를 남긴다.
     */
    private void markUnknownSafely(
            PaymentConfirmContext context,
            String errorCode,
            String errorMessage
    ) {
        try {
            paymentConfirmTransactionService.markConfirmUnknown(
                    context,
                    errorCode,
                    errorMessage
            );

        } catch (RuntimeException exception) {
            log.error(
                    "결제 UNKNOWN 기록 실패. paymentId={}, correlationId={}",
                    context.paymentId(),
                    context.correlationId(),
                    exception
            );
        }
    }
}