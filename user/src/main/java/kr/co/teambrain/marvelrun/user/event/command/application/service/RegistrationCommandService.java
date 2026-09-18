package kr.co.teambrain.marvelrun.user.event.command.application.service;


import kr.co.teambrain.marvelrun.user.capacity.command.application.service.RegistrationCapacityService;
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


}