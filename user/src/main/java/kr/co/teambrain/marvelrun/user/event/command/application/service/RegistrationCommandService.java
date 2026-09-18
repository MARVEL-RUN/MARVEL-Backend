package kr.co.teambrain.marvelrun.user.event.command.application.service;


import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.ReservationReleaseService;

import java.util.HashSet;
import java.util.Set;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.RegistrationCapacityService;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationCreateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationApplyValidator;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.RegistrationCreateResponse;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategoryCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
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

    private final PaymentCreator
            paymentCreator;

    private final ServerTimeProvider serverTimeProvider;


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

        BigDecimal contractAmount = eventCategory.getAmount();

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
    @Transactional
    public RegistrationCreateResponse prepareRepayment(
            String eventId,
            String registrationId
    ) {

        /*
         * 같은 대회의 신청 생성 및 재결제 준비 순서를 맞춘다.
         * 시각은 잠금 대기 이후 구하여 기간 검증에 사용한다.
         */
        registrationCapacityService.lockEvent(eventId);

        LocalDateTime now =
                serverTimeProvider.currentDateTime();

        Registration registration =
                registrationCommandRepository.findById(registrationId)
                        .orElseThrow(
                                () -> new CustomException(
                                        ErrorCode.PAYMENT_NOT_CONFIRMABLE,
                                        " 재결제 대상 신청을 찾을 수 없습니다."
                                )
                        );

        if (
                !eventId.equals(registration.getEvent().getId())
                        || registration.getOrganization() != null
        ) {
            throw new CustomException(
                    ErrorCode.PAYMENT_NOT_CONFIRMABLE,
                    " 해당 대회의 개인 신청이 아닙니다."
            );
        }

        registrationCapacityService.prepareForRepayment(
                registration.getEvent(),
                List.of(registration),
                now
        );

        /*
         * 기존 주문을 READY로 되돌리지 않고 새 주문을 생성한다.
         * 생성 실패 시 기존 주문 무효화와 재확보도 함께 롤백된다.
         */
        Payment payment =
                paymentCreator.createInitialPayment(
                        registration,
                        UUID.randomUUID().toString()
                );

        return RegistrationCreateResponse.from(
                registration,
                payment
        );
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