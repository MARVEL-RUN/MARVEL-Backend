package kr.co.teambrain.marvelrun.user.event.command.application.service;


import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationCreateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationApplyValidator;
import kr.co.teambrain.marvelrun.user.payment.command.domain.Payment;
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
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RegistrationCommandService {

    private static final long PAYMENT_PENDING_MINUTES = 30L;

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


    @Transactional
    public RegistrationCreateResponse register(
            String eventId,
            RegistrationCreateRequest request
    ) {

        /*
         * 신청 생성에 필요한 Entity 조회,
         * Event / Category / Souvenir 검증,
         * Souvenir size 정규화까지 Validator에서 완료한다.
         */
        RegistrationCreateContext context =
                registrationApplyValidator
                        .validate(
                                eventId,
                                request
                        );


        Event event =
                context.event();


        EventCategory eventCategory =
                context.eventCategory();


        BigDecimal contractAmount =
                eventCategory.getAmount();


        LocalDateTime expiresAt =
                calculatePaymentExpiresAt(
                        event
                );


        Registration registration =
                Registration.createForPaymentMvp(
                        event,
                        eventCategory,
                        context.souvenirJsons(),
                        request,
                        contractAmount,
                        expiresAt
                );


        registrationCommandRepository.save(
                registration
        );


        String correlationId =
                UUID.randomUUID()
                        .toString();


        Payment payment =
                paymentCreator
                        .createInitialPayment(
                                registration,
                                correlationId
                        );


        return RegistrationCreateResponse.from(
                registration,
                payment
        );
    }


    private LocalDateTime calculatePaymentExpiresAt(
            Event event
    ) {

        LocalDateTime pendingExpiresAt =
                LocalDateTime.now()
                        .plusMinutes(
                                PAYMENT_PENDING_MINUTES
                        );

        /*
         * Registration 자체의 결제 대기시간보다
         * Event.paymentDeadline이 먼저 오면
         * 대회의 결제 마감시각을 우선한다.
         */
        if (pendingExpiresAt.isAfter(
                event.getPaymentDeadline()
        )) {

            return event.getPaymentDeadline();
        }

        return pendingExpiresAt;
    }
}