package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.CapacityType;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityHoldRequest;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.CapacityCommandRepository;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationCommandRepository;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationApplyValidator;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.valid.EventPaymentPolicyValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentCommandRepository;

import java.util.HashSet;
import java.util.Set;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;

/**
 * 신청 생성 흐름과 Capacity 확보 처리를 연결한다.
 *
 * 대회 잠금, 어린이 정원 대상 판정, 개인·단체 자원 확보,
 * 전체 정원 도달에 따른 대회 마감을 담당한다.
 *
 * 신청 생성 서비스의 트랜잭션에 참여하며,
 * 자체적인 별도 트랜잭션이나 외부 결제 호출은 수행하지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class RegistrationCapacityService {

    private final RegistrationApplyValidator registrationApplyValidator;
    private final EventPaymentPolicyValidator eventPaymentPolicyValidator;

    private final EventCommandRepository eventRepository;
    private final CapacityCommandRepository capacityRepository;
    private final CapacityHoldService capacityHoldService;
    private final ReservationCommandRepository reservationRepository;
    private final PaymentCommandRepository paymentCommandRepository;

    /**
     * 신규 신청의 검증과 저장에 앞서 대회 행을 잠근다.
     *
     * 이후 기존 Validator가 같은 트랜잭션에서 대회를 조회하고,
     * 최신 신청 상태와 기간을 검증하도록 한다.
     *
     * @param eventId 신청 대상 대회
     * @throws CustomException 대회가 존재하지 않는 경우
     */
    public void lockEvent(String eventId) {
        eventRepository.findByIdForUpdate(eventId)
                .orElseThrow(
                        () -> new CustomException(ErrorCode.EVENT_NOT_FOUND)
                );
    }

    /**
     * 검증과 저장을 마친 신청들의 자원을 확보하고,
     * 전체 정원 도달 시 접수를 마감한다.
     *
     * 대회 및 신청의 필수 정보는 앞선 검증 결과를 신뢰한다.
     * 처리 대상이 없는 경우만 차단한다.
     *
     * 자원 확보와 신청 저장, 최초 결제 생성은 동일 트랜잭션에서 처리한다.
     */
    public void holdAndCloseIfFull(
            Event event,
            List<Registration> registrations,
            LocalDateTime now
    ) {
        if (registrations.isEmpty()) {
            throw new CustomException(
                    ErrorCode.INVALID_RESERVATION_ARGUMENT,
                    " 자원 확보 대상 신청 목록이 비어 있습니다."
            );
        }

        LocalDate eventDate = event.getStartDate().toLocalDate();

        List<CapacityHoldRequest> holdRequests =
                new ArrayList<>(registrations.size());

        for (Registration registration : registrations) {
            holdRequests.add(
                    new CapacityHoldRequest(
                            registration,
                            isChild(registration.getBirth(), eventDate)
                    )
            );
        }

        capacityHoldService.holdAll(
                event.getId(),
                holdRequests,
                now
        );

        long fullTotalCount =
                capacityRepository.countFullTotalCapacities(
                        event.getId(),
                        CapacityType.EVENT_TOTAL
                );

        if (fullTotalCount > 0) {
            event.closeRegistrationForCapacity();
        }
    }

    /**
     * 반환된 미결제 신청의 정책을 다시 검증하고 자원을 재확보한다.
     *
     * 현재 신청·결제 기간과 참가 정책을 검증한 뒤,
     * 기존 Reservation을 재사용하여 수량을 확보한다.
     * 전체 정원에 도달하면 최초 신청과 동일하게 대회를 마감한다.
     *
     * 호출자는 대회 잠금과 기존 주문 처리를 먼저 수행해야 한다.
     * 새 Payment 생성까지 동일 트랜잭션에서 처리해야 하며,
     * 실패 시 재확보와 대회 상태 변경도 함께 롤백한다.
     */
    public void reacquireAndCloseIfFull(
            Event event,
            List<Registration> registrations,
            LocalDateTime now
    ) {

        /*
         * 저장된 신청을 기준으로 현재 정책을 검증한다.
         * 신규 신청 중복 검사나 계약금액 재계산은 수행하지 않는다.
         */
        registrationApplyValidator.validateReacquisition(
                event,
                registrations,
                now
        );

        /*
         * 재확보 후 새 결제를 시작할 수 없는 시점이라면
         * 수량을 다시 점유하기 전에 거절한다.
         * 기존 확보를 자동 반환하거나 신청을 삭제하는 처리는 아니다.
         */
        eventPaymentPolicyValidator.validateNewPayment(
                event,
                now
        );

        LocalDate eventDate =
                event.getStartDate().toLocalDate();

        List<CapacityHoldRequest> holdRequests =
                new ArrayList<>(registrations.size());

        for (Registration registration : registrations) {

            holdRequests.add(
                    new CapacityHoldRequest(
                            registration,
                            isChild(
                                    registration.getBirth(),
                                    eventDate
                            )
                    )
            );
        }

        /*
         * RELEASED 여부와 예약 버전은 재확보 내부에서 검증한다.
         * 하나라도 수량이 부족하면 모든 변경을 롤백한다.
         */
        capacityHoldService.reacquireAll(
                event.getId(),
                holdRequests,
                now
        );

        long fullTotalCount =
                capacityRepository.countFullTotalCapacities(
                        event.getId(),
                        CapacityType.EVENT_TOTAL
                );

        if (fullTotalCount > 0) {
            event.closeRegistrationForCapacity();
        }
    }

    /**
     * 최초 미결제 신청들의 기존 주문을 무효화하고 재결제에 필요한 확보를 준비한다.
     *
     * HELD는 기존 점유 수량을 유지하고, RELEASED만 재확보한다.
     * 결제 진행 중이거나 이미 확정된 예약은 처리하지 않는다.
     *
     * 호출자는 대회 잠금을 먼저 획득하고,
     * 이 메서드 이후 새 Payment 생성까지 동일 트랜잭션으로 처리해야 한다.
     */
    public void prepareForRepayment(
            Event event,
            List<Registration> registrations,
            LocalDateTime now
    ) {

        if (registrations.isEmpty()) {
            throw new CustomException(
                    ErrorCode.INVALID_RESERVATION_ARGUMENT,
                    " 재결제 대상 신청 목록이 비어 있습니다."
            );
        }

        Set<String> registrationIds = new HashSet<>();

        for (Registration registration : registrations) {

            if (
                    !event.getId().equals(
                            registration.getEvent().getId()
                    )
                            || registration.isSoftDeleted()
                            || registration.getStatus()
                            != RegistrationStatus.PAYMENT_PENDING
            ) {
                throw new CustomException(
                        ErrorCode.RESERVATION_STATE_CONFLICT,
                        " 재결제 가능한 최초 미결제 신청이 아닙니다."
                                + " registrationId=" + registration.getId()
                );
            }

            if (!registrationIds.add(registration.getId())) {
                throw new CustomException(
                        ErrorCode.INVALID_RESERVATION_ARGUMENT,
                        " 재결제 대상 신청이 중복되었습니다."
                                + " registrationId=" + registration.getId()
                );
            }
        }

        /*
         * 기존 HELD를 유지하는 경우에도 새로운 결제 주문이므로
         * 결제 마감은 검증한다.
         */
        eventPaymentPolicyValidator.validateNewPayment(
                event,
                now
        );

        /*
         * 승인 시작과 동일하게 Payment를 먼저 잠근다.
         * 기존 READY 주문을 무효화하여 이전 화면의 승인을 차단한다.
         * CONFIRMING, UNKNOWN, COMPLETED 주문은 도메인 메서드에서 거절한다.
         */
        List<Payment> payments =
                paymentCommandRepository
                        .findAllRelatedToRegistrationsForUpdate(
                                registrationIds,
                                PaymentPurpose.REGISTRATION_TRY
                        );

        for (Payment payment : payments) {
            payment.invalidateForReservationRelease();
        }

        List<Reservation> reservations =
                reservationRepository.findAllByRegistrationIds(
                        registrationIds
                );

        if (reservations.size() != registrationIds.size()) {
            throw new CustomException(
                    ErrorCode.RESERVATION_NOT_FOUND,
                    " 재결제 대상 신청 중 예약이 누락되어 있습니다."
            );
        }

        List<Registration> releasedRegistrations =
                new ArrayList<>();

        for (Reservation reservation : reservations) {

            ReservationStatus status =
                    reservation.getStatus();

            if (status == ReservationStatus.HELD) {
                /*
                 * 이미 확보한 수량은 다시 증가시키지 않는다.
                 * 기존 확보 회차와 상세도 유지한다.
                 */
                continue;
            }

            if (status == ReservationStatus.RELEASED) {
                releasedRegistrations.add(
                        reservation.getRegistration()
                );
                continue;
            }

            throw new CustomException(
                    ErrorCode.RESERVATION_STATE_CONFLICT,
                    " 현재 예약 상태에서는 재결제를 준비할 수 없습니다."
                            + " reservationId=" + reservation.getId()
                            + ", status=" + status
            );
        }

        /*
         * 반환된 참가자만 현재 정책 검증과 수량 재확보를 수행한다.
         * 단체 중 일부만 반환된 경우에도 HELD 참가자의 수량은 유지한다.
         */
        if (!releasedRegistrations.isEmpty()) {
            reacquireAndCloseIfFull(
                    event,
                    releasedRegistrations,
                    now
            );
        }
    }

    /**
     * 검증된 생년월일을 기준으로 대회일에 만 12세 이하인지 판정한다.
     *
     * 13번째 생일 당일부터 어린이 정원을 적용하지 않는다.
     * 생년월일 형식과 신청 가능 여부는 기존 Validator에서 검증한다.
     */
    private boolean isChild(String birthText, LocalDate eventDate) {
        LocalDate birth = LocalDate.parse(birthText);
        return eventDate.isBefore(birth.plusYears(13));
    }
}