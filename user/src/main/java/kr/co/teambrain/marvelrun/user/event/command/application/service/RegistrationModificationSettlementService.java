package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.json_object.ReservationHistoryEntry;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.ReservationAllocation;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.CapacityCommandRepository;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationCommandRepository;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationItemCommandRepository;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.AdditionalPaymentTargetResolver;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentAllocationCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentAllocationTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;
import java.time.LocalDateTime;
import java.util.*;
import kr.co.teambrain.marvelrun.user.payment.command.application.ModificationRefundPreparationService;

/**
 * 수정된 신청의 금융 상태를 결정하고 최초·추가 결제 주문과 필요한 환불 시도를 구성한다.
 *
 * 신청·예약·정원 수정 직후 같은 트랜잭션에서 호출한다.
 * 호출 전에 대회·관련 Payment 잠금 및 READY 무효화가 완료되어야 한다.
 *
 * 외부 결제 승인과 환불 통신은 이 트랜잭션에서 실행하지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class RegistrationModificationSettlementService {

    private final RegistrationCommandRepository registrationRepository;
    private final ReservationCommandRepository reservationRepository;
    private final ReservationItemCommandRepository itemRepository;
    private final CapacityCommandRepository capacityRepository;

    private final PaymentCreator paymentCreator;
    private final PaymentAllocationCreator allocationCreator;
    private final AdditionalPaymentTargetResolver additionalTargetResolver;
    private final ModificationRefundPreparationService refundPreparationService;

    /**
     * 수정에 포함된 기존·신규·제거 참가자 전체의 금융 상태를 결정한다.
     *
     * 개인은 organizationId=null 및 참가자 한 명을 전달한다.
     * 단체는 제거 대상도 포함하여 전달한다.
     */
    public RegistrationModificationSettlementResult settle(
            String eventId,
            String organizationId,
            List<String> registrationIds,
            LocalDateTime now
    ) {
        if (registrationIds == null
                || registrationIds.isEmpty()
                || registrationIds.stream().anyMatch(
                id -> id == null || id.isBlank()
        )) {
            throw invalidTarget();
        }

        Set<String> uniqueIds = new TreeSet<>(registrationIds);

        if (uniqueIds.size() != registrationIds.size()
                || (organizationId == null && uniqueIds.size() != 1)) {
            throw invalidTarget();
        }

        List<Registration> registrations = new ArrayList<>();

        for (String registrationId : uniqueIds) {
            Registration registration =
                    registrationRepository.findById(registrationId)
                            .orElseThrow(
                                    () -> new CustomException(
                                            ErrorCode.REGISTRATION_NOT_FOUND
                                    )
                            );

            validateScope(eventId, organizationId, registration);

            registrations.add(registration);
        }

        Map<String, Reservation> reservations =
                loadReservations(uniqueIds);

        List<Registration> initialPaymentTargets = new ArrayList<>();
        List<Registration> consumedTargets = new ArrayList<>();

        List<RegistrationModificationSettlementResult.Member> members =
                new ArrayList<>();

        for (Registration registration : registrations) {
            Reservation reservation =
                    reservations.get(registration.getId());

            ReservationStatus previousReservationStatus =
                    reservation.getStatus();

            BigDecimal balance =
                    registration.reconcileModificationFinancialState(
                            previousReservationStatus
                    );

            /*
             * 실제 승인 없이 참가 확정이 가능한 0원 최초 신청.
             * HELD 상태를 남겨 놓지 않고 자원도 확정한다.
             */
            if (!registration.isSoftDeleted()
                    && previousReservationStatus == ReservationStatus.HELD
                    && balance.signum() == 0) {
                confirmZeroAmountReservation(reservation, eventId, now);
            }

            if (registration.getStatus() == RegistrationStatus.PAYMENT_PENDING) {
                initialPaymentTargets.add(registration);
            }

            if (!registration.isSoftDeleted()
                    && reservation.getStatus() == ReservationStatus.CONSUMED) {
                consumedTargets.add(registration);
            }

            members.add(
                    new RegistrationModificationSettlementResult.Member(
                            registration.getId(),
                            registration.getStatus(),
                            registration.getContractAmount(),
                            registration.getPaidAmount(),
                            balance
                    )
            );
        }

        List<RegistrationModificationSettlementResult.Order> orders =
                createUnifiedOrder(organizationId, initialPaymentTargets, consumedTargets);
        List<RegistrationModificationSettlementResult.Refund> refunds =
                refundPreparationService.prepare(eventId, organizationId, registrations);

        /*
         * 상태 변경·주문·Allocation 저장 오류를 같은 Tx에서 확인한다.
         * 여기서 실패하면 앞 단계의 READY 무효화와 Capacity 이동도 롤백된다.
         */
        registrationRepository.flush();

        return new RegistrationModificationSettlementResult(
                members,
                orders,
                refunds
        );
    }

    /**
     * 후속 처리 대상이 요청 대회와 개인·단체 범위에 속하는지 확인한다.
     */
    private void validateScope(
            String eventId,
            String organizationId,
            Registration registration
    ) {
        if (!Objects.equals(
                eventId,
                registration.getEvent().getId()
        )) {
            throw invalidTarget();
        }

        Organization organization = registration.getOrganization();

        if (organizationId == null) {
            if (organization != null) {
                throw invalidTarget();
            }
        } else if (organization == null
                || !organizationId.equals(organization.getId())) {
            throw invalidTarget();
        }
    }

    /**
     * 모든 처리 대상에 예약이 정확히 하나씩 존재하는지 확인한다.
     */
    private Map<String, Reservation> loadReservations(
            Set<String> registrationIds
    ) {
        Map<String, Reservation> result = new HashMap<>();

        for (Reservation reservation :
                reservationRepository.findAllByRegistrationIds(
                        registrationIds
                )) {

            String registrationId =
                    reservation.getRegistration().getId();

            if (!registrationIds.contains(registrationId)
                    || result.putIfAbsent(
                    registrationId,
                    reservation
            ) != null) {
                throw invalidTarget();
            }
        }

        if (!result.keySet().equals(registrationIds)) {
            throw new CustomException(ErrorCode.RESERVATION_NOT_FOUND);
        }

        return result;
    }

    /** 최초 참가비와 추가금이 함께 있으면 한 주문으로 합치고 환불 귀속은 별도로 유지한다. */
    private List<RegistrationModificationSettlementResult.Order> createUnifiedOrder(
            String organizationId, List<Registration> initialTargets, List<Registration> consumedTargets) {
        List<PaymentAllocationTarget> additionalTargets = additionalTargetResolver.resolve(consumedTargets);
        if (initialTargets.isEmpty()) {
            return createAdditionalOrder(organizationId, consumedTargets);
        }
        if (additionalTargets.isEmpty()) {
            return createInitialOrder(organizationId, initialTargets);
        }
        if (organizationId == null) {
            throw invalidTarget();
        }
        List<PaymentAllocationTarget> targets = new ArrayList<>();
        for (Registration registration : initialTargets) {
            targets.add(new PaymentAllocationTarget(registration, registration.getContractAmount(),
                    PaymentPurpose.REGISTRATION_TRY));
        }
        for (PaymentAllocationTarget target : additionalTargets) {
            targets.add(new PaymentAllocationTarget(target.registration(), target.amount(),
                    PaymentPurpose.ADDITIONAL_PAYMENT));
        }
        targets.sort(Comparator.comparing(target -> target.registration().getId()));
        BigDecimal amount = targets.stream().map(PaymentAllocationTarget::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Payment payment = paymentCreator.createMixedPayment(initialTargets.get(0).getOrganization(),
                amount, UUID.randomUUID().toString());
        allocationCreator.create(payment, targets);
        return List.of(new RegistrationModificationSettlementResult.Order(
                payment.getId(), payment.getOrderId(), payment.getOrderName(), payment.getAmount()));
    }

    /**
     * 최초 미결제 대상만 포함하는 새 주문과 Allocation을 생성한다.
     *
     * 이 메서드는 최초 결제만 있는 경우에 사용하며 환불액을 차감하지 않는다.
     * 최초 미결제 대상이 없으면 0원 주문을 만들지 않는다.
     */
    private List<RegistrationModificationSettlementResult.Order> createInitialOrder(
            String organizationId,
            List<Registration> targets
    ) {
        if (targets.isEmpty()) {
            return List.of();
        }

        String correlationId = UUID.randomUUID().toString();

        Payment payment;

        if (organizationId == null) {
            payment = paymentCreator.createInitialPayment(
                    targets.get(0),
                    correlationId
            );
        } else {
            BigDecimal amount = targets.stream()
                    .map(Registration::getContractAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            payment = paymentCreator.createInitialPayment(
                    targets.get(0).getOrganization(),
                    amount,
                    correlationId
            );
        }

        List<PaymentAllocationTarget> allocationTargets =
                targets.stream()
                        .map(registration ->
                                new PaymentAllocationTarget(
                                        registration,
                                        registration.getContractAmount()
                                )
                        )
                        .toList();

        allocationCreator.create(payment, allocationTargets);

        return List.of(
                new RegistrationModificationSettlementResult.Order(
                        payment.getId(),
                        payment.getOrderId(),
                        payment.getOrderName(),
                        payment.getAmount()
                )
        );
    }

    /**
     * 추가 결제만 있는 요청의 양수 부족액을 주문과 귀속으로 저장한다.
     * 환불액·제거 구성원·최초 미결제 대상과 상계하지 않는다.
     * 호출 전 전체 수정 경로에서 기존 READY 주문을 무효화해야 한다.
     */
    private List<RegistrationModificationSettlementResult.Order> createAdditionalOrder(
            String organizationId,
            List<Registration> consumedTargets
    ) {
        List<PaymentAllocationTarget> targets = additionalTargetResolver.resolve(consumedTargets);
        if (targets.isEmpty()) {
            return List.of();
        }
        BigDecimal amount = targets.stream().map(PaymentAllocationTarget::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String correlationId = UUID.randomUUID().toString();
        Payment payment = organizationId == null
                ? paymentCreator.createAdditionalPayment(targets.get(0).registration(), amount, correlationId)
                : paymentCreator.createAdditionalPayment(targets.get(0).registration().getOrganization(), amount, correlationId);
        allocationCreator.create(payment, targets);
        return List.of(new RegistrationModificationSettlementResult.Order(
                payment.getId(), payment.getOrderId(), payment.getOrderName(), payment.getAmount()));
    }

    /**
     * 0원 최초 신청의 저장된 점유를 홀딩에서 확정 수량으로 이동한다.
     *
     * 실제 결제 성공으로 기록하지 않으며 전용 이력을 사용한다.
     * 예약·카운터 중 하나라도 실패하면 전체 롤백한다.
     */
    private void confirmZeroAmountReservation(
            Reservation reservation,
            String eventId,
            LocalDateTime now
    ) {
        Map<String, Integer> quantities = new TreeMap<>();

        List<ReservationAllocation> allocations =
                itemRepository.findAllocations(
                        List.of(reservation.getId())
                );

        for (ReservationAllocation allocation : allocations) {
            if (!reservation.getId().equals(allocation.reservationId())
                    || allocation.capacityId() == null
                    || allocation.capacityId().isBlank()
                    || allocation.quantity() <= 0) {
                throw new CustomException(
                        ErrorCode.CAPACITY_COUNTER_MISMATCH
                );
            }

            if (quantities.putIfAbsent(
                    allocation.capacityId(),
                    allocation.quantity()
            ) != null) {
                throw new CustomException(
                        ErrorCode.CAPACITY_COUNTER_MISMATCH
                );
            }
        }

        if (quantities.isEmpty()) {
            throw new CustomException(
                    ErrorCode.CAPACITY_COUNTER_MISMATCH
            );
        }

        reservation.consumeWithoutPayment();

        reservation.appendHistory(
                ReservationHistoryEntry.Action.ZERO_AMOUNT_CONFIRMED,
                now,
                null,
                "수정 후 참가비 0원으로 결제 없이 참가 확정",
                List.of()
        );

        reservationRepository.flush();

        for (Map.Entry<String, Integer> entry : quantities.entrySet()) {
            int updated = capacityRepository.confirmHeld(
                    eventId,
                    entry.getKey(),
                    entry.getValue(),
                    now
            );

            if (updated != 1) {
                throw new CustomException(
                        ErrorCode.CAPACITY_COUNTER_MISMATCH
                );
            }
        }
    }

    /**
     * 수정 범위를 벗어나거나 중복된 처리 대상을 표현한다.
     */
    private CustomException invalidTarget() {
        return new CustomException(
                ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET
        );
    }
}
