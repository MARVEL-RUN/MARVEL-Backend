package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;
import kr.co.teambrain.marvelrun.user.payment.command.domain.Payment;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.payment.command.domain.PaymentProcessLog;
import kr.co.teambrain.marvelrun.user.payment.command.domain.repository.PaymentProcessLogCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.application.util.PaymentOrderIdGenerator;
import kr.co.teambrain.marvelrun.user.payment.command.domain.repository.PaymentCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 본 클래스는 의도적으로 Transactional을 소유하지 않으므로, Service 가 아닌 Creator로 명명합니다. */
@Component
@RequiredArgsConstructor
public class PaymentCreator {

    private final PaymentCommandRepository paymentCommandRepository;

    private final PaymentProcessLogCommandRepository paymentProcessLogCommandRepository;

    private final PaymentOrderIdGenerator paymentOrderIdGenerator;


    /**
     * 최초 참가 신청에 대한 Payment를 생성한다.
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
                createOrderName(registration);

        /*
         * 이후 Toss confirm 요청에서 사용할 멱등키.
         *
         * Payment 생성 시 한 번 발급하고,
         * 동일 Payment confirm 재시도 시 새로 만들지 않고
         * 저장된 값을 계속 사용한다.
         */
        String confirmIdempotencyKey =
                UUID.randomUUID().toString();


        Payment payment =
                Payment.prepare(
                        registration,
                        orderId,
                        orderName,
                        registration.getContractAmount(),
                        PaymentPurpose.REGISTRATION,
                        confirmIdempotencyKey
                );


        /*
         * Registration과 동일 Transaction에서 INSERT된다.
         */
        Payment savedPayment =
                paymentCommandRepository.save(payment);


        savePaymentPreparedLog(
                registration,
                savedPayment,
                correlationId
        );


        return savedPayment;
    }


    /**
     * Payment 생성 완료 이력을 append-only 로그로 저장한다.
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
     * Toss 결제창에 표시할 주문명을 생성한다.
     *
     * MVP에서는 대회명 + 종목명 정도만 사용한다.
     */
    private String createOrderName(
            Registration registration
    ) {

        return registration.getEvent().getNameKr()
                + " - "
                + registration.getEventCategory().getName();
    }
}