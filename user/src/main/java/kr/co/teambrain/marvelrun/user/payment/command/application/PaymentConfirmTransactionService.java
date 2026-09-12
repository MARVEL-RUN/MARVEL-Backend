package kr.co.teambrain.marvelrun.user.payment.command.application;

import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_log.PaymentProcessSource;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_log.PaymentProcessType;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentConfirmContext;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentConfirmRequest;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentConfirmResponse;
import kr.co.teambrain.marvelrun.user.payment.command.application.exception.InvalidTossSuccessResponseException;
import kr.co.teambrain.marvelrun.user.payment.command.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.domain.PaymentProcessLog;
import kr.co.teambrain.marvelrun.user.payment.command.domain.repository.PaymentCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.domain.repository.PaymentProcessLogCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.dto.TossPaymentConfirmResponse;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.exception.TossPaymentApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class PaymentConfirmTransactionService {

    private final PaymentCommandRepository
            paymentCommandRepository;

    private final PaymentProcessLogCommandRepository
            paymentProcessLogCommandRepository;


    /*
     * Tx 1
     *
     * Toss 호출 전
     * READY → CONFIRMING
     */
    /**
     * Toss confirm 호출 직전에 수행하는 첫 번째 DB Transaction.
     *
     * 역할:
     * 1. orderId 기준 Payment 조회
     * 2. Client가 전달한 amount와 서버 Payment.amount 정합성 검증
     * 3. READY 상태의 Payment만 confirm 가능하도록 검증
     * 4. Toss 인증으로 발급된 paymentKey를 Payment에 선저장
     * 5. Payment 상태를 READY -> CONFIRMING으로 변경
     * 6. CONFIRM_REQUESTED 로그 저장
     * 7. Transaction 밖의 Toss HTTP 호출에 필요한 값만 Context로 반환
     *
     * 이 메서드 안에서는 Toss HTTP 요청을 절대 수행하지 않는다.
     * 메서드 종료와 함께 Tx1이 COMMIT된 뒤 외부 Toss API를 호출한다.
     */
    @Transactional
    public PaymentConfirmContext beginConfirm(
            PaymentConfirmRequest request,
            String correlationId
    ) {

        Payment payment =
                paymentCommandRepository
                        .findByOrderId(
                                request.orderId()
                        )
                        .orElseThrow(
                                () -> new CustomException(
                                        ErrorCode.PAYMENT_NOT_FOUND
                                )
                        );


        /*
         * Client가 가져온 amount는 신뢰하지 않는다.
         */
        BigDecimal requestedAmount =
                BigDecimal.valueOf(
                        request.amount()
                );

        if (payment.getAmount()
                .compareTo(requestedAmount) != 0) {

            throw new CustomException(
                    ErrorCode.PAYMENT_AMOUNT_MISMATCH
            );
        }


        /*
         * 최초 confirm만 허용.
         *
         * CONFIRMING / COMPLETED / UNKNOWN 등이면
         * 중복 HTTP 호출을 바로 발생시키지 않는다.
         */
        if (payment.getProcessStatus()
                != PaymentProcessStatus.READY) {

            throw new CustomException(
                    ErrorCode.PAYMENT_NOT_CONFIRMABLE
            );
        }


        /*
         * paymentKey를 Toss 호출 전에 저장한다.
         *
         * 이후 프로세스가 죽더라도
         * reconciliation에서 Toss Payment 조회에 사용 가능.
         */
        payment.startConfirm(
                request.paymentKey()
        );


        PaymentProcessLog processLog =
                PaymentProcessLog.confirmRequested(
                        payment,
                        correlationId
                );

        paymentProcessLogCommandRepository.save(
                processLog
        );


        return new PaymentConfirmContext(
                payment.getId(),
                payment.getRegistration().getId(),
                payment.getPaymentKey(),
                payment.getOrderId(),
                payment.getAmount().longValueExact(),
                payment.getConfirmIdempotencyKey(),
                correlationId
        );
    }


    /*
     * Tx 2
     *
     * Toss confirm 성공에 따른 Payment 처리내역
     * CONFIRMING -> COMPLETED
     */
    /**
     * Toss confirm API가 HTTP 성공 응답을 반환한 이후 수행하는 두 번째 DB Transaction.
     *
     * 역할:
     * 1. Payment를 다시 조회
     * 2. Toss 성공 응답과 Tx1에서 확정한 Payment 정보 재검증
     * 3. Payment를 CONFIRMING -> COMPLETED로 변경
     * 4. Toss 결제상태, 결제수단, 승인시각, transactionKey 등을 반영
     * 5. Registration.paidAmount 및 RegistrationStatus 갱신
     * 6. CONFIRM_SUCCEEDED 로그 저장
     * 7. 최종 API 응답 DTO 생성
     *
     * Toss 성공 응답의 정합성이 맞지 않으면
     * InvalidTossSuccessResponseException을 발생시켜 이 Transaction을 rollback한다.
     *
     * 호출 Orchestrator는 해당 예외를 잡고 별도의 Transaction에서
     * Payment를 UNKNOWN으로 전환해야 한다.
     */
    @Transactional
    public PaymentConfirmResponse completeConfirm(
            PaymentConfirmContext context,
            TossPaymentConfirmResponse tossResponse
    ) {

        Payment payment =
                paymentCommandRepository
                        .findById(context.paymentId())
                        .orElseThrow(
                                () -> new CustomException(
                                        ErrorCode.PAYMENT_NOT_FOUND
                                )
                        );


        /*
         * Toss HTTP는 성공했지만
         * 실제 response도 우리 Payment와 동일한지 최종 검증.
         */
        validateSuccessResponse(
                context,
                tossResponse
        );


        payment.completeConfirm(
                tossResponse
        );


        Registration registration =
                payment.getRegistration();


        registration.applySuccessfulPayment(
                payment.getAmount()
        );


        paymentProcessLogCommandRepository.save(
                PaymentProcessLog.confirmSucceeded(
                        payment,
                        context.correlationId()
                )
        );


        return PaymentConfirmResponse.from(
                registration,
                payment
        );
    }


    /**
     * Toss로부터 "결제가 실제로 완료되지 않았음"을 명확하게 확인한 경우
     * Payment를 FAILED로 확정하는 Transaction.
     *
     * 역할:
     * 1. Payment 재조회
     * 2. processStatus를 FAILED로 변경
     * 3. Toss HTTP status / errorCode / errorMessage를 포함한
     *    CONFIRM_FAILED 로그 저장
     *
     * timeout, 연결 종료, 결과가 불명확한 응답 등
     * 실제 승인 여부를 확정할 수 없는 상황에서는 이 메서드를 사용하면 안 된다.
     * 그러한 경우에는 markConfirmUnknown()을 사용한다.
     */
    @Transactional
    public void failConfirm(
            PaymentConfirmContext context,
            TossPaymentApiException exception
    ) {

        Payment payment =
                paymentCommandRepository
                        .findById(
                                context.paymentId()
                        )
                        .orElseThrow();

        payment.failConfirm();


        PaymentProcessLog log =
                PaymentProcessLog.builder()

                        .registrationId(
                                context.registrationId()
                        )

                        .paymentId(
                                context.paymentId()
                        )

                        .orderId(
                                context.orderId()
                        )

                        .paymentKey(
                                context.paymentKey()
                        )

                        .idempotencyKey(
                                context.idempotencyKey()
                        )

                        .correlationId(
                                context.correlationId()
                        )

                        .processType(
                                PaymentProcessType.CONFIRM_FAILED
                        )

                        .source(
                                PaymentProcessSource.API
                        )

                        .httpStatus(
                                exception.getHttpStatus()
                        )

                        .errorCode(
                                exception.getTossErrorCode()
                        )

                        .errorMessage(
                                exception.getTossErrorMessage()
                        )

                        .build();


        paymentProcessLogCommandRepository.save(log);
    }

    /**
     * Toss 결제가 실제로 성공했는지 실패했는지
     * 현재 MarvelRun 서버가 확정할 수 없는 경우 사용하는 Transaction.
     *
     * 대표적인 대상:
     * - Toss confirm 요청 이후 Read Timeout
     * - Connection Reset 등 응답 유실
     * - 이미 처리된 결제(ALREADY_PROCESSED_PAYMENT)
     * - Toss 성공 응답을 받았지만 로컬 데이터와 정합성이 맞지 않는 경우
     *
     * 역할:
     * 1. Payment 재조회
     * 2. processStatus를 UNKNOWN으로 변경
     * 3. CONFIRM_UNKNOWN 로그 저장
     *
     * UNKNOWN에서는 Registration.paidAmount를 임의로 증가시키거나
     * 새로운 Payment를 즉시 생성하면 안 된다.
     *
     * 이후 동일 Idempotency-Key 재시도 또는
     * Toss 결제 조회/Reconciliation으로 실제 금융 상태를 확정한다.
     */
    @Transactional
    public void markConfirmUnknown(
            PaymentConfirmContext context,
            String errorCode,
            String errorMessage
    ) {

        Payment payment =
                paymentCommandRepository
                        .findById(
                                context.paymentId()
                        )
                        .orElseThrow();

        payment.markConfirmUnknown();


        PaymentProcessLog log =
                PaymentProcessLog.builder()

                        .registrationId(
                                context.registrationId()
                        )

                        .paymentId(
                                context.paymentId()
                        )

                        .orderId(
                                context.orderId()
                        )

                        .paymentKey(
                                context.paymentKey()
                        )

                        .idempotencyKey(
                                context.idempotencyKey()
                        )

                        .correlationId(
                                context.correlationId()
                        )

                        .processType(
                                PaymentProcessType.CONFIRM_UNKNOWN
                        )

                        .source(
                                PaymentProcessSource.API
                        )

                        .errorCode(errorCode)
                        .errorMessage(errorMessage)

                        .build();


        paymentProcessLogCommandRepository.save(log);
    }


    /* toss -> 서버로 응답받은 '성공 내역' 검증
     * 단, 여기서  Exception을 던져도 외부로 보내면 안된다.
     * Toss에서 200 떴으면 거기선 결제 되었는데, 우리쪽에서 문제가 발생하는 식으로 정합성 깨질 수 있음
     * */
    /**
     * Toss confirm 성공 응답이 Tx1에서 확정한 Payment와
     * 동일한 결제인지 최종 검증한다.
     *
     * 검증 대상:
     * - paymentKey
     * - orderId
     * - totalAmount
     * - Toss payment status == DONE
     *
     * 하나라도 일치하지 않으면 InvalidTossSuccessResponseException을 발생시킨다.
     *
     * 주의:
     * 이 검증은 Toss HTTP 200 이후 수행되므로
     * 실패했다고 Payment를 FAILED 처리하면 안 된다.
     *
     * Toss에서는 이미 금융 승인이 완료되었을 수 있으므로
     * 호출자가 UNKNOWN으로 전환한 뒤 실제 Toss 상태를 재확인해야 한다.
     */
    private void validateSuccessResponse(
            PaymentConfirmContext context,
            TossPaymentConfirmResponse response
    ) {

        if (!context.paymentKey()
                .equals(response.paymentKey())) {

            throw new InvalidTossSuccessResponseException();
        }


        if (!context.orderId()
                .equals(response.orderId())) {

            throw new InvalidTossSuccessResponseException();
        }


        if (context.amount()
                != response.totalAmount()) {

            throw new InvalidTossSuccessResponseException();
        }


        if (!"DONE".equals(
                response.status()
        )) {

            throw new InvalidTossSuccessResponseException();
        }
    }

}