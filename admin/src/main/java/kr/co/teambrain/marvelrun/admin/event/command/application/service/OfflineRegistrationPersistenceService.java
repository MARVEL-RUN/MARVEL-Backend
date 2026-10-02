package kr.co.teambrain.marvelrun.admin.event.command.application.service;

import jakarta.persistence.EntityManager;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.dto.CapacityHoldRequest;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.service.*;
import kr.co.teambrain.marvelrun.admin.event.command.application.context.OfflineRegistrationContext;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.*;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.*;
import kr.co.teambrain.marvelrun.admin.event.command.application.exception.OfflineRegistrationImportException;
import kr.co.teambrain.marvelrun.admin.event.command.application.valid.OfflineRegistrationImportValidator;
import kr.co.teambrain.marvelrun.admin.event.command.repository.EventCategoryCommandRepository;
import kr.co.teambrain.marvelrun.admin.payment.command.application.domain.PaymentAllocation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

/** 파일 전체의 최종 충돌 검증과 금융·예약 원장 저장을 하나의 트랜잭션으로 묶는다. */
@Service
@RequiredArgsConstructor
public class OfflineRegistrationPersistenceService {
    private final EntityManager entityManager;
    private final RegistrationCapacityService registrationCapacityService;
    private final OfflineRegistrationCapacityService offlineCapacityService;
    private final CapacityHoldService capacityHoldService;
    private final ReservationPaymentService reservationPaymentService;
    private final OfflineRegistrationImportValidator validator;
    private final EventCategoryCommandRepository categories;

    /** 외부 결제 완료 신청을 일괄 저장하며 중간 flush도 커밋하지 않는다. */
    @Transactional
    public OfflineRegistrationImportResponse persistOfflinePaidRegistrations(String eventId,
            OfflineRegistrationImportResult result, Map<Integer, String> passwordHashes, LocalDateTime now) {
        // 동일 대회 생성 요청을 직렬화하고 최신 중복과 모든 정원을 다시 확인한다.
        Event event = registrationCapacityService.lockRegistrationEvent(eventId);
        List<OfflineRegistrationContext> contexts = result.contexts();
        validator.validateOfflineDuplicates(eventId, contexts, result);
        List<Map<String, Integer>> requirements = offlineCapacityService
                .collectOfflineCapacityErrors(eventId, contexts, result, true);
        if (!result.errors().isEmpty()) {
            throw new OfflineRegistrationImportException(result.canceledCount(), result.errors());
        }

        // 한 참가자에 하나의 결제와 전액 배분을 만들고 PG 전용 필드는 비워둔다.
        List<CapacityHoldRequest> holds = new ArrayList<>();
        List<Payment> payments = new ArrayList<>();
        List<OfflineRegistrationImportResponse.Success> successes = new ArrayList<>();
        for (OfflineRegistrationContext context : contexts) {
            Registration registration = Registration.createOfflinePaidRegistration(event,
                    categories.getReferenceById(context.categoryId()), context,
                    passwordHashes.get(context.rowNumber()), now);
            entityManager.persist(registration);
            Payment payment = Payment.createOfflineCompletedPayment(registration, context.approvedAt());
            entityManager.persist(payment);
            entityManager.persist(PaymentAllocation.create(payment, registration, context.amount()));
            holds.add(new CapacityHoldRequest(registration, context.child()));
            payments.add(payment);
            successes.add(new OfflineRegistrationImportResponse.Success(context.rowNumber(), registration.getId(), payment.getId(), context.amount()));
            if (payments.size() % 100 == 0) { entityManager.flush(); }
        }

        // 정원 확보와 예약 확정을 완료해야만 전체 파일의 트랜잭션이 커밋된다.
        List<Reservation> reservations = capacityHoldService.holdRegistrationCapacities(eventId, holds, requirements, now);
        reservationPaymentService.confirmRegistrationPayments(eventId, reservations, payments, requirements, now);
        registrationCapacityService.closeRegistrationIfCapacityFull(event);
        entityManager.flush();
        return new OfflineRegistrationImportResponse("SUCCESS", contexts.size(), result.canceledCount(), successes, List.of());
    }
}
