package kr.co.teambrain.marvelrun.user.payment.command.application.creator;

import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_log.PaymentProcessSource;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_log.PaymentProcessType;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.payment.command.application.generator.PaymentOrderIdGenerator;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.PaymentProcessLog;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentProcessLogCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/**
 * 최초·추가·혼합 결제에 필요한 Payment와 생성 이력을 구성하여 저장한다.
 *
 * 참가신청 전체 Use Case를 지휘하지 않고,
 * RegistrationCommandService 또는 OrgRegistrationCommandService가
 * 이미 검증·확정한 신청 정보를 기반으로 Payment 생성 책임만 수행한다.
 *
 * 별도의 Transaction을 시작하지 않으며 호출자의 신청 Transaction에 참여한다.
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

    /**
     * 최신 부족액과 일치하는 개인 추가 주문을 생성한다.
     * 호출자가 권한·예약 상태·잠금·기존 주문·취소 충돌을 먼저 검증해야 한다.
     * Allocation은 같은 트랜잭션에서 기존 PaymentAllocationCreator로 생성한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Payment createAdditionalPayment(
            Registration registration,
            BigDecimal amount,
            String correlationId
    ) {
        validateAdditionalAmount(amount);
        if (registration == null || registration.getId() == null
                || registration.isSoftDeleted()
                || registration.getStatus() != RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED
                || registration.getContractAmount() == null
                || registration.getContractAmount().signum() < 0
                || registration.getPaidAmount() == null
                || registration.getPaidAmount().signum() < 0
                || registration.getContractAmount().subtract(registration.getPaidAmount())
                    .compareTo(amount) != 0) {
            throw new CustomException(ErrorCode.REGISTRATION_FINANCIAL_STATE_INVALID);
        }
        Payment payment = Payment.createInitial(
                registration,
                paymentOrderIdGenerator.generate(),
                createOrderName(registration),
                amount,
                PaymentPurpose.ADDITIONAL_PAYMENT,
                UUID.randomUUID().toString()
        );
        Payment saved = paymentCommandRepository.save(payment);
        savePaymentPreparedLog(registration, saved, correlationId);
        return saved;
    }

    /**
     * 대상 참가자의 양수 부족액 합계로 단체 추가 주문을 생성한다.
     * 기존 완료 주문과 최초 미결제 주문을 수정하지 않는다.
     * 전달 금액과 참가자 귀속 합계는 같은 트랜잭션의 AllocationCreator가 검증한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Payment createAdditionalPayment(
            Organization organization,
            BigDecimal amount,
            String correlationId
    ) {
        validateAdditionalAmount(amount);
        if (organization == null || organization.getId() == null) {
            throw new CustomException(ErrorCode.REGISTRATION_FINANCIAL_STATE_INVALID);
        }
        Payment payment = Payment.createOrgInitial(
                organization,
                amount,
                paymentOrderIdGenerator.generate(),
                createOrderName(organization),
                PaymentPurpose.ADDITIONAL_PAYMENT,
                UUID.randomUUID().toString()
        );
        Payment saved = paymentCommandRepository.save(payment);
        savePaymentPreparedLog(saved, correlationId);
        return saved;
    }

    /** DB 금액 정밀도를 초과하거나 양수가 아닌 추가 주문 금액을 거절한다. */
    private void validateAdditionalAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0
                || amount.stripTrailingZeros().scale() > 2
                || amount.compareTo(new BigDecimal("9999999999.99")) > 0) {
            throw new CustomException(ErrorCode.REGISTRATION_FINANCIAL_STATE_INVALID);
        }
    }

    /** 최초 참가비와 추가 납부액을 한 번에 받는 단체 주문을 생성한다. 환불액은 포함하지 않는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Payment createMixedPayment(Organization organization, BigDecimal amount, String correlationId) {
        validateAdditionalAmount(amount);
        if (organization == null || organization.getId() == null) {
            throw new CustomException(ErrorCode.REGISTRATION_FINANCIAL_STATE_INVALID);
        }
        Payment payment = Payment.createOrgInitial(organization, amount,
                paymentOrderIdGenerator.generate(), createOrderName(organization),
                PaymentPurpose.MIXED_PAYMENT, UUID.randomUUID().toString());
        Payment saved = paymentCommandRepository.save(payment);
        savePaymentPreparedLog(saved, correlationId);
        return saved;
    }
}
