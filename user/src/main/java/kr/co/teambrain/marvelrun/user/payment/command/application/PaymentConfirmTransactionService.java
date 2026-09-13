package kr.co.teambrain.marvelrun.user.payment.command.application;

import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_log.PaymentProcessSource;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_log.PaymentProcessType;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
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
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PaymentConfirmTransactionService {

    private final PaymentCommandRepository
            paymentCommandRepository;

    private final PaymentProcessLogCommandRepository
            paymentProcessLogCommandRepository;

    private final RegistrationCommandRepository
            registrationCommandRepository;


    /**
     * Tx1.
     *
     * Toss confirm HTTP 호출 전에
     * Payment의 현재 상태와 Client 요청값을 검증하고
     * READY -> CONFIRMING 상태로 전환한다.
     *
     * 이 메서드 종료 후 Transaction은 COMMIT된다.
     * Toss HTTP는 이 Transaction 밖에서 수행한다.
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


        BigDecimal requestedAmount =
                BigDecimal.valueOf(
                        request.amount()
                );


        if (
                payment.getAmount()
                        .compareTo(
                                requestedAmount
                        ) != 0
        ) {

            throw new CustomException(
                    ErrorCode.PAYMENT_AMOUNT_MISMATCH
            );
        }


        if (
                payment.getProcessStatus()
                        != PaymentProcessStatus.READY
        ) {

            throw new CustomException(
                    ErrorCode.PAYMENT_NOT_CONFIRMABLE
            );
        }


        validatePaymentTargetBeforeConfirm(
                payment
        );


        String registrationId =
                resolveRegistrationId(
                        payment
                );


        String organizationId =
                resolveOrganizationId(
                        payment
                );


        payment.startConfirm(
                request.paymentKey()
        );


        PaymentProcessLog processLog =
                PaymentProcessLog.builder()

                        .registrationId(
                                registrationId
                        )

                        .paymentId(
                                payment.getId()
                        )

                        .orderId(
                                payment.getOrderId()
                        )

                        .paymentKey(
                                payment.getPaymentKey()
                        )

                        .idempotencyKey(
                                payment.getConfirmIdempotencyKey()
                        )

                        .correlationId(
                                correlationId
                        )

                        .processType(
                                PaymentProcessType.CONFIRM_REQUESTED
                        )

                        .source(
                                PaymentProcessSource.API
                        )

                        .build();


        paymentProcessLogCommandRepository.save(
                processLog
        );


        return new PaymentConfirmContext(
                payment.getId(),
                registrationId,
                organizationId,
                payment.getPaymentKey(),
                payment.getOrderId(),
                payment.getAmount()
                        .longValueExact(),
                payment.getConfirmIdempotencyKey(),
                correlationId
        );
    }


    /**
     * Tx2.
     *
     * Toss confirm HTTP가 성공한 이후
     * MarvelRun DB에 승인 성공을 확정한다.
     *
     * 개인 Payment:
     * Registration 한 건에 Payment.amount를 반영한다.
     *
     * 단체 Payment:
     * Organization 소속 Registration 전체를 조회하고,
     * 각 Registration 자신의 contractAmount를 paidAmount에 반영한다.
     */
    @Transactional
    public PaymentConfirmResponse completeConfirm(
            PaymentConfirmContext context,
            TossPaymentConfirmResponse tossResponse
    ) {

        Payment payment =
                paymentCommandRepository
                        .findById(
                                context.paymentId()
                        )
                        .orElseThrow(
                                () -> new CustomException(
                                        ErrorCode.PAYMENT_NOT_FOUND
                                )
                        );


        /*
         * Tx1 이후 DB target이 비정상적으로 변경되었는지 검증.
         *
         * 이 시점은 Toss HTTP 200 이후이므로
         * 문제 발견 시 단순 FAILED가 아니라 UNKNOWN 복구 흐름으로 보내야 한다.
         */
        validatePaymentTargetAfterTossSuccess(
                payment
        );


        validateContextTarget(
                context,
                payment
        );


        /*
         * Toss가 반환한 결제 정보가
         * Tx1에서 검증한 Payment와 동일한지 검증한다.
         */
        validateSuccessResponse(
                context,
                tossResponse
        );


        if (
                payment.isRegistrationPayment()
        ) {

            Registration registration =
                    payment.getRegistration();


            payment.completeConfirm(
                    tossResponse
            );


            registration.applySuccessfulPayment(
                    payment.getAmount()
            );


            saveConfirmSucceededLog(
                    context,
                    payment
            );


            return PaymentConfirmResponse.fromRegistration(
                    context.registrationId(),
                    payment
            );
        }


        List<Registration> registrations =
                registrationCommandRepository
                        .findAllByOrganization_Id(
                                context.organizationId()
                        );


        /*
         * Toss에서는 이미 승인에 성공했다.
         *
         * 그런데 현재 Organization의 Registration 합계와
         * 실제 결제금액이 다르다면 로컬 정합성이 깨진 상태다.
         *
         * 이 경우 Transaction을 rollback시키고
         * 호출 Orchestrator가 Payment를 UNKNOWN으로 전환한다.
         */
        validateOrganizationPaymentAmount(
                payment,
                registrations
        );


        payment.completeConfirm(
                tossResponse
        );


        for (
                Registration registration
                : registrations
        ) {

            registration.applySuccessfulPayment(
                    registration.getContractAmount()
            );
        }


        saveConfirmSucceededLog(
                context,
                payment
        );


        return PaymentConfirmResponse.fromOrganization(
                context.organizationId(),
                registrations,
                payment
        );
    }


    /**
     * Toss가 결제를 실제로 완료하지 않았다는 사실을
     * 명확히 반환한 경우 FAILED 처리한다.
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
                        .orElseThrow(
                                () -> new CustomException(
                                        ErrorCode.PAYMENT_NOT_FOUND
                                )
                        );


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


        paymentProcessLogCommandRepository.save(
                log
        );
    }


    /**
     * Toss 결제 성공/실패 여부를
     * 현재 MarvelRun이 확정할 수 없는 경우 UNKNOWN 처리한다.
     *
     * 이 단계에서는 Registration의 paidAmount를 변경하지 않는다.
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
                        .orElseThrow(
                                () -> new CustomException(
                                        ErrorCode.PAYMENT_NOT_FOUND
                                )
                        );


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

                        .errorCode(
                                errorCode
                        )

                        .errorMessage(
                                errorMessage
                        )

                        .build();


        paymentProcessLogCommandRepository.save(
                log
        );
    }


    /**
     * Toss 호출 전에 Payment target XOR을 검증한다.
     *
     * Toss 호출 전이므로 잘못된 Payment는
     * PAYMENT_NOT_CONFIRMABLE로 즉시 차단한다.
     */
    private void validatePaymentTargetBeforeConfirm(
            Payment payment
    ) {

        boolean hasRegistration =
                payment.isRegistrationPayment();


        boolean hasOrganization =
                payment.isOrgPayment();


        if (
                hasRegistration
                        == hasOrganization
        ) {

            throw new CustomException(
                    ErrorCode.PAYMENT_NOT_CONFIRMABLE
            );
        }
    }


    /**
     * Toss HTTP 성공 이후 Payment target의 정합성을 검증한다.
     *
     * 이미 외부 금융승인이 발생했을 수 있으므로
     * 이 시점의 불일치는 FAILED가 아니라 UNKNOWN 대상이다.
     */
    private void validatePaymentTargetAfterTossSuccess(
            Payment payment
    ) {

        boolean hasRegistration =
                payment.isRegistrationPayment();


        boolean hasOrganization =
                payment.isOrgPayment();


        if (
                hasRegistration
                        == hasOrganization
        ) {

            throw new InvalidTossSuccessResponseException();
        }
    }


    /**
     * Tx1에서 확정한 target과
     * Tx2에서 다시 조회한 Payment target이 동일한지 검증한다.
     */
    private void validateContextTarget(
            PaymentConfirmContext context,
            Payment payment
    ) {

        if (
                payment.isRegistrationPayment()
        ) {

            String registrationId =
                    resolveRegistrationId(
                            payment
                    );


            if (
                    !Objects.equals(
                            context.registrationId(),
                            registrationId
                    )
                            || context.organizationId() != null
            ) {

                throw new InvalidTossSuccessResponseException();
            }


            return;
        }


        String organizationId =
                resolveOrganizationId(
                        payment
                );


        if (
                !Objects.equals(
                        context.organizationId(),
                        organizationId
                )
                        || context.registrationId() != null
        ) {

            throw new InvalidTossSuccessResponseException();
        }
    }


    /**
     * 단체 최초 결제금액과
     * 현재 Organization 소속 Registration 계약금액 합계를 비교한다.
     */
    private void validateOrganizationPaymentAmount(
            Payment payment,
            List<Registration> registrations
    ) {

        if (
                registrations == null
                        || registrations.isEmpty()
        ) {

            throw new InvalidTossSuccessResponseException();
        }


        BigDecimal totalContractAmount =
                registrations.stream()

                        .map(
                                Registration::getContractAmount
                        )

                        .reduce(
                                BigDecimal.ZERO,
                                BigDecimal::add
                        );


        if (
                totalContractAmount.compareTo(
                        payment.getAmount()
                ) != 0
        ) {

            throw new InvalidTossSuccessResponseException();
        }
    }


    /**
     * 개인 Payment의 Registration target ID.
     *
     * 일반적인 Hibernate LAZY Proxy에서는
     * getId()만으로 대상 Entity 본문 SELECT가 발생하지 않는다.
     */
    private String resolveRegistrationId(
            Payment payment
    ) {

        if (
                !payment.isRegistrationPayment()
        ) {

            return null;
        }


        return payment.getRegistration()
                .getId();
    }


    /**
     * 단체 Payment의 Organization target ID.
     */
    private String resolveOrganizationId(
            Payment payment
    ) {

        if (
                !payment.isOrgPayment()
        ) {

            return null;
        }


        return payment.getOrganization()
                .getId();
    }


    /**
     * 승인 성공 로그 저장.
     *
     * 현재 PaymentProcessLog에는 organizationId 필드가 없으므로
     * 단체 Payment는 registrationId=null로 기록하고
     * paymentId를 금융 처리의 기준 식별자로 사용한다.
     */
    private void saveConfirmSucceededLog(
            PaymentConfirmContext context,
            Payment payment
    ) {

        PaymentProcessLog processLog =
                PaymentProcessLog.builder()

                        .registrationId(
                                context.registrationId()
                        )

                        .paymentId(
                                payment.getId()
                        )

                        .orderId(
                                payment.getOrderId()
                        )

                        .paymentKey(
                                payment.getPaymentKey()
                        )

                        .idempotencyKey(
                                payment.getConfirmIdempotencyKey()
                        )

                        .correlationId(
                                context.correlationId()
                        )

                        .processType(
                                PaymentProcessType.CONFIRM_SUCCEEDED
                        )

                        .source(
                                PaymentProcessSource.API
                        )

                        .build();


        paymentProcessLogCommandRepository.save(
                processLog
        );
    }


    /**
     * Toss confirm 성공 응답이
     * Tx1에서 확정한 Payment와 동일한 결제인지 검증한다.
     */
    private void validateSuccessResponse(
            PaymentConfirmContext context,
            TossPaymentConfirmResponse response
    ) {

        if (
                !context.paymentKey()
                        .equals(
                                response.paymentKey()
                        )
        ) {

            throw new InvalidTossSuccessResponseException();
        }


        if (
                !context.orderId()
                        .equals(
                                response.orderId()
                        )
        ) {

            throw new InvalidTossSuccessResponseException();
        }


        if (
                context.amount()
                        != response.totalAmount()
        ) {

            throw new InvalidTossSuccessResponseException();
        }


        if (
                !"DONE".equals(
                        response.status()
                )
        ) {

            throw new InvalidTossSuccessResponseException();
        }
    }
}