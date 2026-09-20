package kr.co.teambrain.marvelrun.user.event.command.application.service;


import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.ReservationReleaseService;

import kr.co.teambrain.marvelrun.user.capacity.command.application.service.RegistrationCapacityService;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationCreateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationApplyValidator;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentAllocationCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.RegistrationCreateResponse;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategoryCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentAllocationTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;

/**
 * 개인 신청, 자원 예약, 최초 결제를 하나의 트랜잭션에서 생성한다.
 *
 * 대회를 잠근 뒤 기존 정책 검증을 수행하고,
 * 필요한 정원과 기념품 확보에 성공한 신청만 저장을 확정한다.
 */
@Service
@RequiredArgsConstructor
public class RegistrationCommandService {

    private final RegistrationCapacityService registrationCapacityService;

    private final ReservationReleaseService reservationReleaseService;

    private final EventCommandRepository
            eventCommandRepository;

    private final EventCategoryCommandRepository
            eventCategoryCommandRepository;

    private final RegistrationCommandRepository
            registrationCommandRepository;

    private final RegistrationApplyValidator
            registrationApplyValidator;

    private final RegistrationPricingService
            registrationPricingService;

    private final PaymentCreator
            paymentCreator;

    private final PaymentAllocationCreator
            paymentAllocationCreator;

    private final ServerTimeProvider serverTimeProvider;

    private final PaymentCommandRepository paymentCommandRepository;


    /**
     * 개인 신청을 생성하고 정원·기념품을 임시 확보한다.
     *
     * 대회 잠금 → 정책 검증 → 신청 저장 → 자원 확보 및 마감
     * → 최초 Payment 생성 순서로 처리한다.
     *
     * 어느 단계든 실패하면 동일 트랜잭션의 변경을 모두 롤백한다.
     */
    @Transactional
    public RegistrationCreateResponse register(
            String eventId,
            RegistrationCreateRequest request
    ) {
        LocalDateTime now = serverTimeProvider.currentDateTime();

        registrationCapacityService.lockEvent(eventId);

        RegistrationCreateContext context =
                registrationApplyValidator.validate(
                        eventId,
                        request,
                        now
                );

        Event event = context.event();
        EventCategory eventCategory = context.eventCategory();

        /*
         * Validation을 통과한 신청정보를 기준으로
         * 서버에서 최종 계약금액을 계산한다.
         *
         * Client에서 전달한 금액은 사용하지 않으며,
         * 현재 9/22 MVP에서는 대상 MarvelRun Event의 어린이에게만
         * 40,000원 고정가격을 적용한다.
         */
        BigDecimal contractAmount =
                registrationPricingService.calculateContractAmount(
                        event,
                        eventCategory,
                        request.birth()
                );

        Registration registration =
                Registration.createForPaymentMvp(
                        event,
                        eventCategory,
                        context.souvenirJsons(),
                        request,
                        contractAmount
                );

        Registration savedRegistration =
                registrationCommandRepository.save(registration);

        registrationCapacityService.holdAndCloseIfFull(
                event,
                List.of(savedRegistration),
                now
        );

        String correlationId = UUID.randomUUID().toString();

        Payment payment =
                paymentCreator.createInitialPayment(
                        savedRegistration,
                        correlationId
                );

        /*
         * 개인 Payment도 금융 귀속을 PaymentAllocation으로 통일한다.
         *
         * Payment.registration은 주문의 직접 대상을 나타내는 기존 관계로 유지하고,
         * 실제 금액 귀속 원장은 PaymentAllocation 1건으로 별도 보존한다.
         */
        paymentAllocationCreator.create(
                payment,
                List.of(
                        new PaymentAllocationTarget(
                                registration,
                                registration.getContractAmount()
                        )
                )
        );

        return RegistrationCreateResponse.from(
                savedRegistration,
                payment
        );
    }

    /**
     * 기존 개인 미결제 신청의 재결제 주문을 생성한다.
     *
     * 기존 신청과 계약금액을 유지하며,
     * HELD는 그대로 사용하고 RELEASED는 재확보한 뒤 새 주문을 생성한다.
     *
     * 호출자는 해당 신청의 소유권을 먼저 검증해야 한다.
     * 주문 무효화, 재확보 및 새 주문 생성은 하나의 트랜잭션으로 처리한다.
     */
//    @Transactional
//    public RegistrationCreateResponse prepareRepayment(
//            String eventId,
//            String registrationId
//    ) {
//
//        /*
//         * 같은 대회의 신청 생성 및 재결제 준비 순서를 맞춘다.
//         * 시각은 잠금 대기 이후 구하여 기간 검증에 사용한다.
//         */
//        registrationCapacityService.lockEvent(eventId);
//
//        LocalDateTime now =
//                serverTimeProvider.currentDateTime();
//
//        Registration registration =
//                registrationCommandRepository.findById(registrationId)
//                        .orElseThrow(
//                                () -> new CustomException(
//                                        ErrorCode.PAYMENT_NOT_CONFIRMABLE,
//                                        " 재결제 대상 신청을 찾을 수 없습니다."
//                                )
//                        );
//
//        if (
//                !eventId.equals(registration.getEvent().getId())
//                        || registration.getOrganization() != null
//        ) {
//            throw new CustomException(
//                    ErrorCode.PAYMENT_NOT_CONFIRMABLE,
//                    " 해당 대회의 개인 신청이 아닙니다."
//            );
//        }
//
//        registrationCapacityService.prepareForRepayment(
//                registration.getEvent(),
//                List.of(registration),
//                now
//        );
//
//        /*
//         * 기존 주문을 READY로 되돌리지 않고 새 주문을 생성한다.
//         * 생성 실패 시 기존 주문 무효화와 재확보도 함께 롤백된다.
//         */
//        Payment payment =
//                paymentCreator.createInitialPayment(
//                        registration,
//                        UUID.randomUUID().toString()
//                );
//
//        paymentAllocationCreator.create(
//                payment,
//                List.of(
//                        new PaymentAllocationTarget(
//                                registration,
//                                registration.getContractAmount()
//                        )
//                )
//        );
//
//        return RegistrationCreateResponse.from(
//                registration,
//                payment
//        );
//    }
    // [TO-BE] RegistrationCommandService.java 내 prepareRepayment 수정안 (2-F)

    @Transactional
    public RegistrationCreateResponse prepareRepayment(
            String eventId,
            String registrationId,
            String failedPaymentId // [TO-BE] 실패한 결제 ID를 받아야 명확한 대상을 한정할 수 있음
    ) {

        registrationCapacityService.lockEvent(eventId);

        LocalDateTime now = serverTimeProvider.currentDateTime();

        // 1. 대상을 Registration에서 찾는 게 아니라, 지정된 실패 Payment부터 확인합니다.
        Payment failedPayment = paymentCommandRepository.findById(failedPaymentId)
                .orElseThrow(() -> new CustomException(ErrorCode.PAYMENT_NOT_CONFIRMABLE, " 재결제 대상 주문을 찾을 수 없습니다."));

        if (failedPayment.getPurpose()
                != PaymentPurpose.REGISTRATION_TRY) {
            throw new CustomException(ErrorCode.PAYMENT_NOT_CONFIRMABLE,
                    " 최초 참가비 주문만 이 재결제 경로에서 처리할 수 있습니다.");
        }

        // 2. 명확히 차단해야 할 상태(CONFIRMING, UNKNOWN, COMPLETED)만 걸러내도록 수정
        PaymentProcessStatus status = failedPayment.getProcessStatus();
        if (status == PaymentProcessStatus.CONFIRMING || status == PaymentProcessStatus.UNKNOWN || status == PaymentProcessStatus.COMPLETED) {
            throw new CustomException(ErrorCode.PAYMENT_NOT_CONFIRMABLE, " 처리 중이거나 완료된 주문은 재결제할 수 없습니다.");
        }

        Registration registration = failedPayment.getRegistration();

        if (registration == null || !registration.getId().equals(registrationId)) {
            throw new CustomException(ErrorCode.PAYMENT_NOT_CONFIRMABLE, " 결제 주문의 대상 신청이 일치하지 않습니다.");
        }

        if (!eventId.equals(registration.getEvent().getId()) || registration.getOrganization() != null) {
            throw new CustomException(ErrorCode.PAYMENT_NOT_CONFIRMABLE, " 해당 대회의 개인 신청이 아닙니다.");
        }

        // 3. 실패한 주문에 할당되었던 내역을 바탕으로 다시 확보 준비
        registrationCapacityService.prepareForRepayment(
                registration.getEvent(),
                List.of(registration),
                now
        );

        // 4. 새로운 결제 주문 생성
        Payment payment = paymentCreator.createInitialPayment(
                registration,
                UUID.randomUUID().toString()
        );

        paymentAllocationCreator.create(
                payment,
                List.of(new PaymentAllocationTarget(registration, registration.getContractAmount()))
        );

        return RegistrationCreateResponse.from(registration, payment);
    }

    /**
     * 기존 개인 미결제 신청의 주문을 무효화하고 확보 수량을 반환한다.
     *
     * 신청 행과 신청 상태는 유지한다.
     * 실제 반환과 주문 무효화는 동일 트랜잭션에서 처리한다.
     *
     * 호출자는 해당 신청의 소유권을 검증해야 한다.
     * 단체 소속 신청은 단체 반환 경로에서 처리한다.
     */
    @Transactional
    public void releaseReservation(
            String eventId,
            String registrationId
    ) {

        registrationCapacityService.lockEvent(eventId);

        LocalDateTime now =
                serverTimeProvider.currentDateTime();

        Registration registration =
                registrationCommandRepository.findById(registrationId)
                        .orElseThrow(
                                () -> new CustomException(
                                        ErrorCode.INVALID_RESERVATION_ARGUMENT,
                                        " 반환 대상 신청을 찾을 수 없습니다."
                                )
                        );

        if (
                !eventId.equals(registration.getEvent().getId())
                        || registration.getOrganization() != null
                        || registration.isSoftDeleted()
                        || registration.getStatus()
                        != RegistrationStatus.PAYMENT_PENDING
        ) {
            throw new CustomException(
                    ErrorCode.RESERVATION_STATE_CONFLICT,
                    " 확보를 반환할 수 있는 개인 미결제 신청이 아닙니다."
            );
        }

        /*
         * 관련 Payment 잠금과 무효화 이후 예약 수량을 반환한다.
         * 승인 진행 중이거나 반환할 수 없는 예약이면 전체 롤백된다.
         */
        reservationReleaseService.releaseUnpaid(
                eventId,
                List.of(registrationId),
                now
        );
    }


}