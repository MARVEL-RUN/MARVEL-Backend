package kr.co.teambrain.marvelrun.user.payment.command.application;

import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_log.PaymentProcessSource;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_log.PaymentProcessType;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.ReservationPaymentService;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.PaymentAllocation;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentAllocationCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentConfirmContext;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentConfirmRequest;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentConfirmResponse;
import kr.co.teambrain.marvelrun.user.payment.command.application.exception.InvalidTossSuccessResponseException;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.PaymentProcessLog;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentProcessLogCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.valid.EventPaymentPolicyValidator;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.dto.TossPaymentConfirmResponse;
import kr.co.teambrain.marvelrun.user.payment.command.infrastructure.toss.exception.TossPaymentApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 외부 승인 호출 전후의 로컬 결제 트랜잭션을 처리한다.
 *
 * 승인 시작 시 Payment와 Reservation을 처리 중으로 전환하고,
 * 승인 성공 시 결제·납부금액·예약·정원 수량을 함께 확정한다.
 *
 * 외부 Toss 호출은 이 클래스의 트랜잭션 밖에서 수행한다.
 */
@Service
@RequiredArgsConstructor
public class PaymentConfirmTransactionService {

    private final ServerTimeProvider serverTimeProvider;
    private final PaymentConfirmationAllocationSupport allocationSupport;

    private final ReservationPaymentService reservationPaymentService;

    private final EventPaymentPolicyValidator eventPaymentPolicyValidator;

    private final PaymentCommandRepository
            paymentCommandRepository;

    private final PaymentProcessLogCommandRepository
            paymentProcessLogCommandRepository;

    private final RegistrationCommandRepository
            registrationCommandRepository;

    private final PaymentAllocationCommandRepository
            paymentAllocationCommandRepository;

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
            String correlationId,
            LocalDateTime now
    ) {

        Payment payment = allocationSupport.lockForStart(request.orderId());

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

        /*
         * 새로운 승인 처리를 시작하기 전에 대회 결제 마감을 검증한다.
         *
         * 개인 Payment는 Registration의 Event를,
         * 단체 Payment는 Organization의 Event를 사용한다.
         *
         * 이 검증은 상태 변경과 Toss HTTP 호출 전에만 수행한다.
         */
        Event event =
                resolvePaymentEvent(payment);

        eventPaymentPolicyValidator.validateNewPayment(
                event,
                now
        );

        String registrationId =
                resolveRegistrationId(
                        payment
                );

        String organizationId =
                resolveOrganizationId(
                        payment
                );

        /*
         * 외부 승인 요청 전에 대상 예약을 PROCESSING으로 전환한다.
         *
         * 예약 버전 충돌이 발생하면 현재 트랜잭션을 실패시키고
         * Toss 승인 API는 호출하지 않는다.
         *
         * 내부 flush는 커밋이 아니므로,
         * 이후 Payment 상태 변경이나 로그 저장 실패 시 함께 롤백된다.
         */

        List<PaymentAllocation> allocations = paymentAllocationCommandRepository
                .findAllByPayment_IdOrderByRegistration_IdAsc(payment.getId());

        validateAllocationsBeforeConfirm(payment, allocations, event);

        /*
         * 예약 상태와 결제 시작 이력을 함께 저장한다.
         * Payment 상태 변경 및 요청 로그 저장도 같은 트랜잭션에 포함된다.
         */

        List<String> initialIds = allocationSupport.validateAndGetInitialIds(
                payment, allocations, ReservationStatus.HELD);
        if (!initialIds.isEmpty()) {
            reservationPaymentService.startPayment(
                    initialIds,
                    payment.getId(),
                    now
            );
        }

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
     * Toss 승인 성공 이후 결제, 신청 납부금액, 예약 상태,
     * Capacity 확정 수량을 하나의 트랜잭션으로 반영한다.
     *
     * 이미 완료된 Payment는 기존 결과만 반환하여
     * 납부금액과 확정 수량의 중복 증가를 방지한다.
     *
     * 외부 승인은 이미 발생했을 수 있으므로,
     * 로컬 반영 실패는 호출부에서 UNKNOWN 처리 대상으로 다룬다.
     */
    @Transactional
    public PaymentConfirmResponse completeConfirm(
            PaymentConfirmContext context,
            TossPaymentConfirmResponse tossResponse,
            LocalDateTime now
    ) {

        /*
         * 대회·단체·관련 Payment 순서로 잠가 수정 및 환불과 결과 반영을 직렬화한다.
         *
         * 외부 Toss 호출은 이미 종료된 상태이며,
         * 이 잠금은 현재 로컬 트랜잭션 안에서만 유지된다.
         */
        Payment payment = allocationSupport.lockForResult(context.paymentId());

        /*
         * Tx1 이후 결제 대상이 변경되었거나
         * 개인·단체 대상의 XOR 조건이 깨졌는지 검증한다.
         *
         * 외부 승인 이후 발견된 불일치이므로
         * 단순 결제 실패로 처리하면 안 된다.
         */
        validatePaymentTargetAfterTossSuccess(
                payment
        );

        validateContextTarget(
                context,
                payment
        );

        /*
         * Toss가 반환한 결제키, 주문번호, 금액, 상태가
         * Tx1에서 확정한 승인 요청과 일치하는지 검증한다.
         */
        validateSuccessResponse(
                context,
                tossResponse
        );

        List<PaymentAllocation> allocations = paymentAllocationCommandRepository
                .findAllByPayment_IdOrderByRegistration_IdAsc(payment.getId());
        List<Registration> registrations = allocations.stream().map(PaymentAllocation::getRegistration).toList();

        /*
         * 이미 로컬 확정까지 완료된 결제라면 기존 결과만 반환한다.
         *
         * 아래의 예약 확정, paidAmount 증가,
         * 성공 로그 저장을 다시 수행하지 않는다.
         */
        if (
                payment.getProcessStatus()
                        == PaymentProcessStatus.COMPLETED
        ) {

            if (payment.isRegistrationPayment()) {
                return PaymentConfirmResponse.fromRegistration(
                        registrations.get(0),
                        payment
                );
            }

            return PaymentConfirmResponse.fromOrganization(
                    context.organizationId(),
                    registrations,
                    payment
            );
        }

        /*
         * 승인 처리 중인 결제 또는 결과 확인 중인 결제만 확정한다.
         *
         * UNKNOWN은 이후 승인 성공이 확인되었을 때
         * 같은 확정 처리를 사용할 수 있도록 허용한다.
         */
        if (
                payment.getProcessStatus()
                        != PaymentProcessStatus.CONFIRMING
                        && payment.getProcessStatus()
                        != PaymentProcessStatus.UNKNOWN
        ) {
            throw new CustomException(
                    ErrorCode.PAYMENT_NOT_CONFIRMABLE,
                    " 승인 성공을 반영할 수 없는 상태입니다."
                            + " status=" + payment.getProcessStatus()
            );
        }

        /*
         * 승인한 금액과 해당 주문에 고정된 Allocation 합계가 일치해야 한다.
         *
         * 불일치하면 확정 수량이나 납부금액을 변경하지 않고
         * 트랜잭션을 실패시킨다.
         */

        validateAllocationSumMatchesPaymentAmount(payment, allocations);

        /*
         * 예약을 PROCESSING에서 CONSUMED로 변경하고,
         * 예약 상세에 기록된 수량을 heldCount에서 confirmedCount로 이동한다.
         * 결제 식별자를 예약 이력에 기록하면서 확보 수량을 확정한다.
         *
         * 예약 버전 충돌이나 수량 불일치가 발생하면
         * 이 트랜잭션의 변경을 모두 롤백한다.
         */

        List<String> initialIds = allocationSupport.validateAndGetInitialIds(
                payment, allocations, ReservationStatus.PROCESSING);
        if (!initialIds.isEmpty()) {
            reservationPaymentService.confirmPayment(
                    resolvePaymentEvent(payment).getId(),
                    initialIds,
                    payment.getId(),
                    now
            );
        }

        /*
         * 예약 확정과 동일 트랜잭션에서
         * Payment에 Toss 승인 결과를 반영한다.
         */
        payment.completeConfirm(
                tossResponse
        );

        /*
         * 개인 결제는 해당 신청 한 건에
         * 실제 Payment 금액을 반영한다.
         */

        /*
         * 단체 결제는 전체 Payment 금액을 각 신청에 반복 반영하지 않는다.
         *
         * 각 귀속에 고정된 allocatedAmount만 해당 참가자의 paidAmount에 더한다.
         */

        for (PaymentAllocation allocation : allocations) {
            Registration registration = allocation.getRegistration();
            registration.applySuccessfulPayment(allocation.getAllocatedAmount());
        }

        saveConfirmSucceededLog(
                context,
                payment
        );

        if (payment.isRegistrationPayment()) {
            return PaymentConfirmResponse.fromRegistration(registrations.get(0), payment);
        }

        return PaymentConfirmResponse.fromOrganization(
                context.organizationId(),
                registrations,
                payment
        );
    }

    /**
     * 외부 승인 실패가 명확하게 확인된 결제를 FAILED로 처리한다.
     *
     * 대상 예약은 PROCESSING에서 HELD로 복원한다.
     * Capacity의 홀딩 수량은 반환하지 않는다.
     *
     * 성공 여부를 확정할 수 없는 오류에는 호출하지 않는다.
     */
    @Transactional
    public void failConfirm(
            PaymentConfirmContext context,
            TossPaymentApiException exception
    ) {

        Payment payment = allocationSupport.lockForResult(context.paymentId());

        /*
         * 이미 같은 결제의 실패 반영을 완료했다면
         * 예약 상태 변경과 실패 로그 저장을 반복하지 않는다.
         */
        if (
                payment.getProcessStatus()
                        == PaymentProcessStatus.FAILED
        ) {
            return;
        }

        /*
         * 완료되었거나 결과 확인 중인 결제를
         * 뒤늦은 실패 처리로 덮어쓰지 않는다.
         */
        if (
                payment.getProcessStatus()
                        != PaymentProcessStatus.CONFIRMING
        ) {
            throw new CustomException(
                    ErrorCode.PAYMENT_NOT_CONFIRMABLE,
                    " 승인 실패를 반영할 수 없는 상태입니다."
                            + " status=" + payment.getProcessStatus()
            );
        }

        /*
         * Tx1에서 승인 요청한 대상과 현재 DB의 대상이
         * 동일한지 확인한 후 예약을 복원한다.
         */
        validatePaymentTargetBeforeConfirm(
                payment
        );

        validateContextTarget(
                context,
                payment
        );

        /*
         * 실패 반영 시각을 한 번 구해 대상 예약들의 이력에 공통으로 사용한다.
         * 결제 실패 시점은 승인 요청 시작 시각과 구분한다.
         */
        LocalDateTime failedAt =
                serverTimeProvider.currentDateTime();

        /*
         * 결제 실패는 신청 취소가 아니므로 자원을 반환하지 않는다.
         *
         * 예약만 HELD로 복원하고,
         * heldCount와 confirmedCount는 변경하지 않는다.
         */

        List<PaymentAllocation> allocations = paymentAllocationCommandRepository
                .findAllByPayment_IdOrderByRegistration_IdAsc(payment.getId());
        List<String> initialIds = allocationSupport.validateAndGetInitialIds(
                payment, allocations, ReservationStatus.PROCESSING);
        if (!initialIds.isEmpty()) {
            reservationPaymentService.restoreHeldAfterFailure(
                    initialIds,
                    payment.getId(),
                    failedAt
            );
        }

        payment.failConfirm();

        /*
         * 외부 실패 응답과 내부 결제를 연결할 수 있도록
         * 주문, 결제키, 상관관계 식별자, 오류 정보를 기록한다.
         */
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
     * 외부 승인 결과 또는 로컬 확정 여부가 불명확한 결제를 UNKNOWN으로 표시한다.
     *
     * Reservation은 PROCESSING 상태로 유지하고,
     * 신청 납부금액과 Capacity 수량은 변경하지 않는다.
     *
     * 이미 완료되거나 다른 상태로 처리된 결제를 덮어쓰지 않는다.
     */
    @Transactional
    public void markConfirmUnknown(
            PaymentConfirmContext context,
            String errorCode,
            String errorMessage
    ) {

        Payment payment =
                paymentCommandRepository
                        .findByIdForUpdate(
                                context.paymentId()
                        )
                        .orElseThrow(
                                () -> new CustomException(
                                        ErrorCode.PAYMENT_NOT_FOUND
                                )
                        );

        /*
         * 다른 트랜잭션이 이미 성공 또는 실패를 확정했거나
         * UNKNOWN 표시를 완료했다면 상태를 덮어쓰지 않는다.
         */
        if (
                payment.getProcessStatus()
                        != PaymentProcessStatus.CONFIRMING
        ) {
            return;
        }

        payment.markConfirmUnknown();

        /*
         * 승인 성공 가능성이 남아 있으므로
         * 예약을 HELD나 RELEASED로 변경하지 않는다.
         *
         * 홀딩을 유지한 채 후속 조회·복구에서 결과를 확인한다.
         */
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
     * 단체 최초 결제금액과 현재 구성원의 계약금액 합계를 비교한다.
     *
     * 외부 승인 이후 호출하므로 불일치는
     * 로컬 확정을 중단하고 UNKNOWN 처리로 이어져야 한다.
     */

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

    /**
     * 결제 대상이 소속된 대회를 반환한다.
     *
     * validatePaymentTargetBeforeConfirm()으로
     * 개인·단체 대상의 XOR 검증을 완료한 뒤 호출한다.
     */
    private Event resolvePaymentEvent(
            Payment payment
    ) {
        if (payment.isRegistrationPayment()) {
            return payment.getRegistration().getEvent();
        }

        return payment.getOrganization().getEvent();
    }

    /**
     * 개인 또는 단체 결제의 대상 신청을 반환한다.
     *
     * 호출 전에 Payment 대상의 개인·단체 XOR 검증을 완료해야 한다.
     * 단체 신청은 ID 순서로 정렬하여 후속 처리 순서를 일정하게 유지한다.
     */

    /**
     * Payment Allocation 대상 해석 및 정합성 검증.
     * Allocation 합계와 대상(삭제여부, 대회, 권한 등)을 승인 전에 철저히 확인한다.
     */
    private void validateAllocationsBeforeConfirm(Payment payment, List<PaymentAllocation> allocations, Event event) {
        if (allocations.isEmpty()) {
            throw new CustomException(ErrorCode.PAYMENT_ALLOCATION_INTEGRITY_ERROR, " 결제에 할당된 대상이 없습니다.");
        }

        validateAllocationSumMatchesPaymentAmount(payment, allocations);

        for (PaymentAllocation allocation : allocations) {
            Registration reg = allocation.getRegistration();
            if (reg.isSoftDeleted()) {
                throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET, " 삭제된 참가자가 결제 대상에 포함되어 있습니다.");
            }
            if (!reg.getEvent().getId().equals(event.getId())) {
                throw new CustomException(ErrorCode.PAYMENT_ALLOCATION_INTEGRITY_ERROR, " 결제 대상의 대회가 일치하지 않습니다.");
            }

            if (payment.isRegistrationPayment()) {
                if (allocations.size() > 1 || !reg.getId().equals(payment.getRegistration().getId())) {
                    throw new CustomException(ErrorCode.PAYMENT_ALLOCATION_INTEGRITY_ERROR, " 개인 결제 대상이 일치하지 않습니다.");
                }
            } else if (payment.isOrgPayment()) {
                if (reg.getOrganization() == null || !reg.getOrganization().getId().equals(payment.getOrganization().getId())) {
                    throw new CustomException(ErrorCode.PAYMENT_ALLOCATION_INTEGRITY_ERROR, " 단체 결제 대상의 소속이 일치하지 않습니다.");
                }
            }
        }
    }

    /**
     * Allocation의 할당 금액 총합이 Payment 결제 요청 금액과 동일한지 검증한다.
     */
    private void validateAllocationSumMatchesPaymentAmount(Payment payment, List<PaymentAllocation> allocations) {
        BigDecimal totalAllocated = allocations.stream()
                .map(PaymentAllocation::getAllocatedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalAllocated.compareTo(payment.getAmount()) != 0) {
            if (payment.getProcessStatus() == PaymentProcessStatus.CONFIRMING || payment.getProcessStatus() == PaymentProcessStatus.UNKNOWN) {
                throw new InvalidTossSuccessResponseException();
            } else {
                throw new CustomException(ErrorCode.PAYMENT_ALLOCATION_INTEGRITY_ERROR, " 할당 금액 합계가 결제 금액과 일치하지 않습니다.");
            }
        }
    }
}
