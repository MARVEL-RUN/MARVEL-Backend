package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import kr.co.teambrain.marvelrun.common.json_object.ReservationHistoryEntry;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.ReservationAllocation;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.CapacityCommandRepository;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationCommandRepository;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationItemCommandRepository;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 최초 결제에 따른 예약 상태 변경과 수량 확정을 처리한다.
 *
 * Payment 변경과 동일한 트랜잭션에 참여하며,
 * 외부 결제 API를 호출하거나 독립적으로 커밋하지 않는다.
 *
 * 예약의 동시 상태 변경은 @Version으로 감지하고,
 * Capacity는 ID 순서로 수량을 변경한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ReservationPaymentService {

    private final ReservationCommandRepository reservationRepository;
    private final ReservationItemCommandRepository itemRepository;
    private final CapacityCommandRepository capacityRepository;

    /**
     * 결제 대상 신청들의 예약을 PROCESSING으로 전환한다.
     *
     * 다른 결제 시도가 같은 예약을 먼저 변경했다면
     * flush 시 버전 충돌로 트랜잭션을 실패시킨다.
     * 외부 승인 호출은 이 트랜잭션이 커밋된 뒤 수행해야 한다.
     */
    /**
     * 결제 대상 신청들의 예약을 PROCESSING으로 전환하고 이력을 기록한다.
     *
     * 상태 변경과 JSON 이력은 동일한 예약 버전으로 저장한다.
     * 외부 승인 호출은 현재 트랜잭션이 커밋된 뒤 수행해야 한다.
     */
    public void startPayment(
            List<String> registrationIds,
            String paymentId,
            LocalDateTime now
    ) {
        List<Reservation> reservations =
                loadReservations(registrationIds);

        /*
         * 상태 전환에 성공한 예약에만 결제 시작 이력을 추가한다.
         * 확보 상세는 같은 holdSequence의 HOLD 이력을 참조한다.
         */
        for (Reservation reservation : reservations) {
            reservation.startPayment();

            reservation.appendHistory(
                    ReservationHistoryEntry.Action.PAYMENT_STARTED,
                    now,
                    paymentId,
                    "결제 승인 요청 시작",
                    List.of()
            );
        }

        /*
         * 다른 결제 요청이나 반환 작업과의 버전 충돌을 확인한다.
         * 충돌하면 상태 변경과 추가한 이력이 함께 롤백된다.
         */
        reservationRepository.flush();
    }

    /**
     * 명확한 결제 실패에 따라 예약을 HELD로 복원하고 이력을 기록한다.
     *
     * 확보한 자원은 유지하므로 Capacity 카운터는 변경하지 않는다.
     * 승인 결과가 UNKNOWN인 경우에는 호출하지 않는다.
     */
    public void restoreHeldAfterFailure(
            List<String> registrationIds,
            String paymentId,
            LocalDateTime now
    ) {
        List<Reservation> reservations =
                loadReservations(registrationIds);

        for (Reservation reservation : reservations) {
            reservation.restoreHeldAfterPaymentFailure();

            reservation.appendHistory(
                    ReservationHistoryEntry.Action.PAYMENT_FAILED,
                    now,
                    paymentId,
                    "승인 실패 확인으로 홀딩 상태 복원",
                    List.of()
            );
        }

        /*
         * 예약 상태 복원과 이력 추가를 함께 반영한다.
         * 상세 Toss 오류는 기존 PaymentProcessLog에 기록한다.
         */
        reservationRepository.flush();
    }

    /**
     * 결제 대상 예약을 확정하고 홀딩 수량을 확정 수량으로 이동한다.
     *
     * 모든 예약의 버전 검증 후 Capacity별 수량을 합산한다.
     * 한 행이라도 이동에 실패하면 예약 상태와 수량 변경을 모두 롤백한다.
     */
    public void confirmPayment(
            String eventId,
            List<String> registrationIds,
            String paymentId,
            LocalDateTime now
    ) {
        List<Reservation> reservations =
                loadReservations(registrationIds);

        /*
         * 예약 상태 변경 직후 결제 성공 이력을 추가한다.
         *
         * 아래의 Capacity 수량 이동이 실패하면
         * CONSUMED 상태와 이력 추가도 함께 롤백된다.
         */
        for (Reservation reservation : reservations) {
            reservation.consumeAfterPayment();

            reservation.appendHistory(
                    ReservationHistoryEntry.Action.PAYMENT_CONFIRMED,
                    now,
                    paymentId,
                    "결제 승인 성공에 따른 확보 수량 확정",
                    List.of()
            );
        }

        reservationRepository.flush();

        List<String> reservationIds = reservations.stream()
                .map(Reservation::getId)
                .toList();

        List<ReservationAllocation> allocations =
                itemRepository.findAllocations(reservationIds);

        Set<String> recordedReservationIds = new HashSet<>();
        Map<String, Integer> quantities = new TreeMap<>();

        for (ReservationAllocation allocation : allocations) {
            if (allocation.quantity() <= 0) {
                throw new CustomException(
                        ErrorCode.CAPACITY_COUNTER_MISMATCH,
                        " 예약 상세 수량이 잘못되었습니다."
                                + " reservationId=" + allocation.reservationId()
                                + ", capacityId=" + allocation.capacityId()
                );
            }

            recordedReservationIds.add(allocation.reservationId());

            quantities.merge(
                    allocation.capacityId(),
                    allocation.quantity(),
                    Integer::sum
            );
        }

        if (!recordedReservationIds.equals(
                new HashSet<>(reservationIds)
        )) {
            throw new CustomException(
                    ErrorCode.CAPACITY_COUNTER_MISMATCH,
                    " 확보 상세가 없는 예약이 존재합니다."
            );
        }

        for (Map.Entry<String, Integer> entry : quantities.entrySet()) {
            int updated = capacityRepository.confirmHeld(
                    eventId,
                    entry.getKey(),
                    entry.getValue(),
                    now
            );

            if (updated != 1) {
                throw new CustomException(
                        ErrorCode.CAPACITY_COUNTER_MISMATCH,
                        " 홀딩 수량을 확정 수량으로 이동하지 못했습니다."
                                + " capacityId=" + entry.getKey()
                                + ", quantity=" + entry.getValue()
                );
            }
        }
    }

    /**
     * 결제 대상 신청의 예약을 ID 순서로 조회한다.
     *
     * DB 조회 결과를 기준으로 예약 누락을 확인한다.
     * 비어 있는 처리 대상은 허용하지 않는다.
     */
    private List<Reservation> loadReservations(
            List<String> registrationIds
    ) {
        if (registrationIds.isEmpty()) {
            throw new CustomException(
                    ErrorCode.RESERVATION_NOT_FOUND,
                    " 결제 대상 신청 목록이 비어 있습니다."
            );
        }

        Set<String> uniqueIds = new HashSet<>(registrationIds);

        List<Reservation> reservations =
                reservationRepository.findAllByRegistrationIds(uniqueIds);

        if (reservations.size() != uniqueIds.size()) {
            throw new CustomException(
                    ErrorCode.RESERVATION_NOT_FOUND,
                    " 결제 대상 신청 중 예약이 누락되어 있습니다."
            );
        }

        return reservations;
    }
}