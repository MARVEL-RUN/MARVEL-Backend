package kr.co.teambrain.marvelrun.user.event.command.application.service;


import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.payment.command.domain.Payment;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationCreateRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.RegistrationCreateResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationValidator;
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

    private final RegistrationValidator
            registrationValidator;

    private final PaymentCreator
            paymentCreator;


    @Transactional // 본
    public RegistrationCreateResponse register(
            String eventId,
            RegistrationCreateRequest request
    ) {

        /*
         * 1. Event 조회.
         *
         * Command use-case에서 Registration을 구성하기 위한
         * 도메인 데이터이므로 EventCommandRepository 사용.
         */
        Event event =
                eventCommandRepository
                        .findById(eventId)
                        .orElseThrow(() ->
                                new CustomException(ErrorCode.EVENT_NOT_FOUND)
                        );


        /*
         * 2. 대회 신청 가능 여부 검증.
         *
         * 현재 구현되어 있는 RegistrationValidator 사용.
         */
        registrationValidator
                .validateEventRegistrable(event);


        /*
         * 3. EventCategory 조회.
         */
        EventCategory eventCategory =
                eventCategoryCommandRepository
                        .findById(
                                request.eventCategoryId()
                        )
                        .orElseThrow(() ->
                                new CustomException(ErrorCode.EVENT_CATEGORY_NOT_FOUND)
                        );


        /*
         * 4. 해당 Event의 Category인지,
         * 현재 신청 가능한 Category인지 검증.
         */
        registrationValidator
                .validateCategory(
                        event,
                        eventCategory
                );


        /*
         * 5. 참가비는 Client 값을 사용하지 않는다.
         *
         * EventCategory.amount가 Source of Truth.
         */
        BigDecimal contractAmount =
                eventCategory.getAmount();


        /*
         * 6. 최초 결제 대기 만료시각.
         */
        LocalDateTime expiresAt =
                calculatePaymentExpiresAt(event);


        /*
         * 7. Registration 생성.
         */
        Registration registration =
                Registration.createForPaymentMvp(
                        event,
                        eventCategory,
                        request,
                        contractAmount,
                        expiresAt
                );

        registrationCommandRepository.save(
                registration
        );


        /*
         * 8. 최초 Payment와 PAYMENT_PREPARED 로그를
         * 동일 Transaction에서 생성.
         */
        String correlationId = String.valueOf(UUID.randomUUID());
        Payment payment =
                paymentCreator
                        .createInitialPayment(
                                registration,
                                correlationId
                        );


        /*
         * 9. Front에서 바로 Toss 결제 인증을
         * 시작할 수 있는 데이터 반환.
         */
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