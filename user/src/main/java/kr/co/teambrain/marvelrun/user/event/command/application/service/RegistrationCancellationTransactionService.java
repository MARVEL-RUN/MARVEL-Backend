package kr.co.teambrain.marvelrun.user.event.command.application.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import kr.co.teambrain.marvelrun.common.inheritance_enum.EventStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.ReservationRemovalService;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationCommandRepository;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.PaymentCancel;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult.Member;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult.Refund;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.support.OrganizationLockSupport;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationAccessVerifier;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentCancelCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 참가 취소·정원 반환·환불 준비를 같은 DB 트랜잭션에 저장한다. 외부 PG는 호출하지 않는다. */
@Service
@RequiredArgsConstructor
public class RegistrationCancellationTransactionService {
    private final EntityManager entityManager;
    private final EventCommandRepository events;
    private final RegistrationModificationPaymentGuard paymentGuard;
    private final ReservationCommandRepository reservations;
    private final ReservationRemovalService removal;
    private final RegistrationModificationSettlementService settlement;
    private final PaymentCancelCommandRepository cancellations;
    private final ServerTimeProvider time;

    /** 개인 신청을 현재 정보로 인증하며 단체 구성원의 개인 취소 진입을 차단한다. */
    @Transactional
    public Prepared cancelPersonal(String eventId, String registrationId, RegistrationAccessRequest access) {
        Event event = lockEvent(eventId);
        List<Payment> payments = paymentGuard.lockPersonal(eventId, registrationId);
        Registration registration = entityManager.find(Registration.class, registrationId);
        if (registration == null) { throw new CustomException(ErrorCode.REGISTRATION_NOT_FOUND); }
        entityManager.refresh(registration, LockModeType.PESSIMISTIC_WRITE);
        if (registration.getOrganization() != null || !eventId.equals(registration.getEvent().getId())) {
            throw new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED);
        }
        RegistrationAccessVerifier.verifyPersonal(registration, access);
        return prepare(event, null, List.of(registration), payments);
    }

    /** 단체 인증 후 DB의 현재 구성원을 잠금 조회한다. 프론트의 명단·환불액을 받지 않는다. */
    @Transactional
    public Prepared cancelOrganization(String eventId, String organizationId, OrganizationAccessRequest access) {
        Event event = lockEvent(eventId);
        Organization organization = entityManager.find(Organization.class, organizationId);
        if (organization == null) { throw new CustomException(ErrorCode.ORGANIZATION_NOT_FOUND); }
        OrganizationLockSupport.lockWithoutWaiting(entityManager, organizationId);
        entityManager.refresh(organization, LockModeType.PESSIMISTIC_WRITE);
        if (!eventId.equals(organization.getEvent().getId())) {
            throw new CustomException(ErrorCode.ORGANIZATION_ACCESS_DENIED);
        }
        RegistrationAccessVerifier.verifyOrganization(organization, access);
        List<Payment> payments = paymentGuard.lockOrganization(eventId, organizationId);
        List<Registration> members = entityManager.createQuery(
                "select r from Registration r where r.event.id = :eventId and r.organization.id = :organizationId order by r.id",
                Registration.class).setParameter("eventId", eventId).setParameter("organizationId", organizationId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        for (Registration member : members) { entityManager.refresh(member, LockModeType.PESSIMISTIC_WRITE); }
        if (members.isEmpty()) { throw new CustomException(ErrorCode.REGISTRATION_NOT_FOUND); }
        return prepare(event, organizationId, members, payments);
    }

    /** 활성 신청만 새로 취소하고 이미 취소된 행은 재반환·재환불하지 않고 상태를 반환한다. */
    private Prepared prepare(Event event, String organizationId, List<Registration> members, List<Payment> payments) {
        List<Registration> active = members.stream().filter(r -> !r.isSoftDeleted()).toList();
        if (active.isEmpty()) {
            validateCanceled(members);
            return new Prepared(snapshot(members, payments, List.of()), false);
        }
        LocalDateTime now = time.currentDateTime();
        validateWindow(event, now);
        paymentGuard.prepareLockedPayments(payments);
        lockReservations(members);
        validateCanceled(members.stream().filter(Registration::isSoftDeleted).toList());
        for (Registration member : active) { validateActive(member); }
        List<String> activeIds = active.stream().map(Registration::getId).sorted().toList();
        removal.releaseAll(event.getId(), activeIds, now);
        for (Registration member : active) { member.cancelParticipation(); }
        RegistrationModificationSettlementResult settled = settlement.settle(event.getId(), organizationId, activeIds, now);
        entityManager.flush();
        return new Prepared(snapshot(members, payments, settled.refunds()), true);
    }

    /** 취소 허용 기간은 기존 접수 기간을 사용하고 정원 마감(CLOSED)만으로 취소를 막지는 않는다. */
    private void validateWindow(Event event, LocalDateTime now) {
        LocalDateTime start = event.getRegistStartDate();
        LocalDateTime deadline = event.getRegistDeadline();
        if (start == null || deadline == null || !start.isBefore(deadline)) {
            throw new CustomException(ErrorCode.REGISTRATION_FINANCIAL_STATE_INVALID,
                    "취소 기간 판단에 필요한 접수 기간 설정이 올바르지 않습니다.");
        }
        if (event.getEventStatus() != EventStatus.OPEN && event.getEventStatus() != EventStatus.CLOSED) {
            throw new CustomException(ErrorCode.EVENT_NOT_OPEN);
        }
        if (now.isBefore(start)) { throw new CustomException(ErrorCode.EVENT_REGISTRATION_NOT_STARTED); }
        if (!now.isBefore(deadline)) { throw new CustomException(ErrorCode.EVENT_REGISTRATION_CLOSED); }
    }

    /** 지원하는 현재 신청 상태와 금액만 취소한다. 미지정 관리자 상태는 임의 해석하지 않는다. */
    private void validateActive(Registration registration) {
        if (registration.getContractAmount() == null || registration.getContractAmount().signum() < 0
                || registration.getPaidAmount() == null || registration.getPaidAmount().signum() < 0) {
            throw new CustomException(ErrorCode.REGISTRATION_FINANCIAL_STATE_INVALID);
        }
        RegistrationStatus status = registration.getStatus();
        if (status != RegistrationStatus.PAYMENT_PENDING && status != RegistrationStatus.CONFIRMED
                && status != RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED
                && status != RegistrationStatus.PARTIAL_REFUND_REQUIRED && status != RegistrationStatus.EXPIRED) {
            throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_TARGET);
        }
    }

    /** 과거 취소 행이 금융 상태와 모순되면 완료 응답으로 숨기지 않는다. */
    private void validateCanceled(List<Registration> members) {
        for (Registration member : members) {
            if (!member.isSoftDeleted() || member.getContractAmount() == null
                    || member.getContractAmount().signum() != 0 || member.getPaidAmount() == null
                    || member.getPaidAmount().signum() < 0
                    || member.getStatus() != (member.getPaidAmount().signum() == 0
                            ? RegistrationStatus.CANCELED : RegistrationStatus.CANCELLATION_PENDING)) {
                throw new CustomException(ErrorCode.REGISTRATION_FINANCIAL_STATE_INVALID);
            }
        }
    }

    /** 신청 잠금 다음에 예약을 ID 순서로 잠가 기존 정원 반환 서비스에 최신 상태를 전달한다. */
    private void lockReservations(List<Registration> members) {
        List<String> ids = members.stream().map(Registration::getId).toList();
        List<Reservation> rows = new ArrayList<>(reservations.findAllByRegistrationIds(ids));
        rows.sort(Comparator.comparing(Reservation::getId));
        if (rows.size() != ids.size() || rows.stream().map(r -> r.getRegistration().getId()).distinct().count() != ids.size()) {
            throw new CustomException(ErrorCode.RESERVATION_NOT_FOUND);
        }
        for (Reservation reservation : rows) {
            entityManager.refresh(reservation, LockModeType.PESSIMISTIC_WRITE);
            Registration registration = reservation.getRegistration();
            if (!ids.contains(registration.getId())) { throw new CustomException(ErrorCode.INVALID_RESERVATION_ARGUMENT); }
            ReservationStatus status = reservation.getStatus();
            if (status != ReservationStatus.HELD && status != ReservationStatus.CONSUMED
                    && status != ReservationStatus.RELEASED) {
                throw new CustomException(ErrorCode.RESERVATION_STATE_CONFLICT);
            }
            if ((registration.isSoftDeleted() && status != ReservationStatus.RELEASED)
                    || ((status == ReservationStatus.HELD || status == ReservationStatus.RELEASED)
                            && !registration.isSoftDeleted() && registration.getPaidAmount() != null
                            && registration.getPaidAmount().signum() != 0)) {
                throw new CustomException(ErrorCode.REGISTRATION_FINANCIAL_STATE_INVALID);
            }
        }
    }

    /** 원결제별 환불 이력과 현재 참가자 금액을 응답한다. 이력 합계는 이번 신규 환불액이 아니다. */
    private RegistrationModificationSettlementResult snapshot(List<Registration> members, List<Payment> payments, List<Refund> preparedRefunds) {
        List<Member> result = members.stream().sorted(Comparator.comparing(Registration::getId))
                .map(r -> new Member(r.getId(), r.getStatus(), r.getContractAmount(), r.getPaidAmount(),
                        r.getContractAmount().subtract(r.getPaidAmount()))).toList();
        List<String> paymentIds = payments.stream().map(Payment::getId).distinct().sorted().toList();
        List<Refund> refunds = new ArrayList<>();
        if (!paymentIds.isEmpty()) {
            for (PaymentCancel cancel : cancellations.findAllByPaymentIdsForUpdate(paymentIds)) {
                Refund prepared = preparedRefunds.stream().filter(r -> r.paymentCancelId().equals(cancel.getId())).findFirst().orElse(null);
                refunds.add(prepared != null ? prepared : new Refund(cancel.getId(), cancel.getPayment().getId(), cancel.getCancelAmount(),
                        cancel.getStatus(), "cancellation-" + cancel.getId()));
            }
        }
        return new RegistrationModificationSettlementResult(result, List.of(), refunds);
    }

    /** 대회 행을 다른 신청·결제 경로와 같은 첫 잠금으로 사용한다. */
    private Event lockEvent(String eventId) {
        Event event = events.findByIdForUpdate(eventId)
                .orElseThrow(() -> new CustomException(ErrorCode.EVENT_NOT_FOUND));
        entityManager.refresh(event, LockModeType.PESSIMISTIC_WRITE);
        return event;
    }

    /** 새 취소를 저장한 요청만 외부 환불 실행으로 이어지게 하는 커밋 후 전달값이다. */
    public record Prepared(RegistrationModificationSettlementResult result, boolean executeRefunds) { }
}
