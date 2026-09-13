package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_log.PaymentProcessSource;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_log.PaymentProcessType;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.util.PaymentOrderIdGenerator;
import kr.co.teambrain.marvelrun.user.payment.command.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.domain.PaymentProcessLog;
import kr.co.teambrain.marvelrun.user.payment.command.domain.repository.PaymentCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.domain.repository.PaymentProcessLogCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 본 클래스는 의도적으로 Transactional을 소유하지 않으므로,
 * Service가 아닌 Creator로 명명한다.
 */
@Component
@RequiredArgsConstructor
public class PaymentCreator {

    private final PaymentCommandRepository
            paymentCommandRepository;

    private final PaymentProcessLogCommandRepository
            paymentProcessLogCommandRepository;

    private final PaymentOrderIdGenerator
            paymentOrderIdGenerator;


    /**
     * 개인 최초 참가 신청에 대한 Payment를 생성한다.
     *
     * 별도의 Transaction을 열지 않고
     * RegistrationCommandService의 Transaction에 참여한다.
     */
    public Payment createInitialPayment(
            Registration registration,
            String correlationId
    ) {

        String orderId =
                paymentOrderIdGenerator.generate();


        String orderName =
                createOrderName(
                        registration
                );


        String confirmIdempotencyKey =
                UUID.randomUUID()
                        .toString();


        Payment payment =
                Payment.createInitial(
                        registration,
                        orderId,
                        orderName,
                        registration.getContractAmount(),
                        PaymentPurpose.REGISTRATION_TRY,
                        confirmIdempotencyKey
                );


        Payment savedPayment =
                paymentCommandRepository.save(
                        payment
                );


        savePaymentPreparedLog(
                registration,
                savedPayment,
                correlationId
        );


        return savedPayment;
    }


    /**
     * 단체 최초 참가 신청에 대한 Payment를 생성한다.
     *
     * 하나의 Organization에 포함된 모든 Registration의
     * contractAmount 합계를 하나의 Payment.amount로 사용한다. <- amount가 매개변수에서 요구되는 이유
     *
     * 별도의 Transaction을 열지 않고
     * OrgRegistrationCommandService의 Transaction에 참여한다.
     */
    public Payment createInitialPayment(
            Organization organization,
            BigDecimal amount,
            String correlationId
    ) {

        String orderId =
                paymentOrderIdGenerator.generate();


        String orderName =
                createOrderName(
                        organization
                );


        String confirmIdempotencyKey =
                UUID.randomUUID()
                        .toString();


        Payment payment =
                Payment.createOrgInitial(
                        organization,
                        amount,
                        orderId,
                        orderName,
                        PaymentPurpose.REGISTRATION_TRY,
                        confirmIdempotencyKey
                );


        Payment savedPayment =
                paymentCommandRepository.save(
                        payment
                );


        savePaymentPreparedLog(
                savedPayment,
                correlationId
        );


        return savedPayment;
    }


    /**
     * 개인 Payment 생성 완료 이력을 저장한다.
     */
    private void savePaymentPreparedLog(
            Registration registration,
            Payment payment,
            String correlationId
    ) {

        PaymentProcessLog processLog =
                PaymentProcessLog.createPaymentPrepared(
                        registration,
                        payment,
                        correlationId
                );


        paymentProcessLogCommandRepository.save(
                processLog
        );
    }


    /**
     * 단체 Payment 생성 완료 이력을 저장한다.
     *
     * 단체 Payment에는 registrationId가 존재하지 않으므로
     * Payment 자체의 식별정보를 기준으로 로그를 남긴다.
     */
    private void savePaymentPreparedLog(
            Payment payment,
            String correlationId
    ) {

        PaymentProcessLog processLog =
                PaymentProcessLog.builder()
                        .registrationId(
                                null
                        )
                        .paymentId(
                                payment.getId()
                        )
                        .orderId(
                                payment.getOrderId()
                        )
                        .idempotencyKey(
                                payment.getConfirmIdempotencyKey()
                        )
                        .correlationId(
                                correlationId
                        )
                        .processType(
                                PaymentProcessType.PAYMENT_PREPARED
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
     * 개인 Toss 결제창 주문명.
     */
    private String createOrderName(
            Registration registration
    ) {

        return registration.getEvent()
                .getNameKr()
                + " - "
                + registration.getEventCategory()
                .getName();
    }


    /**
     * 단체 Toss 결제창 주문명.
     */
    private String createOrderName(
            Organization organization
    ) {

        return organization.getEvent()
                .getNameKr()
                + " - "
                + organization.getGroupName();
    }
}