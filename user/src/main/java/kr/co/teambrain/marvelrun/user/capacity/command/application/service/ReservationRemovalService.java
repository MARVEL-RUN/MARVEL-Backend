package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
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
 * 개인 참가 취소와 단체 구성원 제거·전체 취소의 실제 자원 점유를 반환한다.
 *
 * 결제 처리와 Registration 삭제는 수행하지 않는다.
 * 호출자는 소유권과 금융 충돌을 검증하고 같은 Tx에서 호출해야 한다.
 *
 * RELEASED 예약의 상세는 과거 확보 정보이므로 다시 반환하지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ReservationRemovalService {

    private final ReservationCommandRepository reservationRepository;
    private final ReservationItemCommandRepository itemRepository;
    private final CapacityCommandRepository capacityRepository;

    /**
     * 제거 대상 예약의 상태와 실제 상세를 기준으로 수량을 반환한다.
     *
     * 예약 상태·이력·카운터는 같은 트랜잭션에서 변경된다.
     * 상세는 보존하되 RELEASED 전환 이후 현재 점유량으로 사용하지 않는다.
     */
    public void releaseAll(
            String eventId,
            List<String> registrationIds,
            LocalDateTime now
    ) {
        if (registrationIds.isEmpty()) {
            return;
        }

        Set<String> uniqueIds = new HashSet<>(registrationIds);

        if (uniqueIds.size() != registrationIds.size()
                || uniqueIds.stream().anyMatch(
                id -> id == null || id.isBlank()
        )) {
            throw new CustomException(
                    ErrorCode.INVALID_RESERVATION_ARGUMENT
            );
        }

        List<Reservation> reservations =
                new ArrayList<>(
                        reservationRepository.findAllByRegistrationIds(
                                uniqueIds
                        )
                );

        reservations.sort(Comparator.comparing(Reservation::getId));

        Set<String> foundRegistrationIds = new HashSet<>();
        Map<String, ReservationStatus> oldStatuses = new TreeMap<>();

        for (Reservation reservation : reservations) {
            String registrationId =
                    reservation.getRegistration().getId();

            if (!uniqueIds.contains(registrationId)
                    || !foundRegistrationIds.add(registrationId)
                    || !eventId.equals(
                    reservation.getRegistration().getEvent().getId()
            )) {
                throw new CustomException(
                        ErrorCode.INVALID_RESERVATION_ARGUMENT
                );
            }

            ReservationStatus oldStatus = reservation.getStatus();

            if (oldStatus != ReservationStatus.HELD
                    && oldStatus != ReservationStatus.CONSUMED
                    && oldStatus != ReservationStatus.RELEASED) {
                throw new CustomException(
                        ErrorCode.RESERVATION_STATE_CONFLICT
                );
            }

            if (oldStatus != ReservationStatus.RELEASED) {
                oldStatuses.put(reservation.getId(), oldStatus);
            }
        }

        if (!foundRegistrationIds.equals(uniqueIds)) {
            throw new CustomException(ErrorCode.RESERVATION_NOT_FOUND);
        }

        if (oldStatuses.isEmpty()) {
            return;
        }

        /*
         * 예약 ID 순서로 실제 UPDATE를 실행한다.
         * 동시 결제·수정이 먼저 반영되면 @Version 검증에서 실패한다.
         */
        for (Reservation reservation : reservations) {
            if (!oldStatuses.containsKey(reservation.getId())) {
                continue;
            }

            reservation.releaseForParticipantRemoval();

            reservation.appendHistory(
                    ReservationHistoryEntry.Action.RELEASE,
                    now,
                    null,
                    "참가 취소 또는 단체 구성원 제거에 따른 자원 반환",
                    List.of()
            );

            reservationRepository.flush();
        }

        Map<String, Map<String, Integer>> quantitiesByReservation =
                new TreeMap<>();

        oldStatuses.keySet().forEach(
                id -> quantitiesByReservation.put(id, new TreeMap<>())
        );

        for (ReservationAllocation allocation :
                itemRepository.findAllocations(oldStatuses.keySet())) {

            Map<String, Integer> quantities =
                    quantitiesByReservation.get(allocation.reservationId());

            if (quantities == null
                    || allocation.capacityId() == null
                    || allocation.capacityId().isBlank()
                    || allocation.quantity() <= 0) {
                throw counterMismatch();
            }

            if (quantities.putIfAbsent(
                    allocation.capacityId(),
                    allocation.quantity()
            ) != null) {
                throw counterMismatch();
            }
        }

        Map<String, Integer> held = new TreeMap<>();
        Map<String, Integer> confirmed = new TreeMap<>();

        for (Map.Entry<String, Map<String, Integer>> entry :
                quantitiesByReservation.entrySet()) {

            if (entry.getValue().isEmpty()) {
                throw counterMismatch();
            }

            Map<String, Integer> target =
                    oldStatuses.get(entry.getKey()) == ReservationStatus.HELD
                            ? held
                            : confirmed;

            entry.getValue().forEach(
                    (capacityId, quantity) ->
                            target.merge(
                                    capacityId,
                                    quantity,
                                    Math::addExact
                            )
            );
        }

        Set<String> capacityIds = new TreeSet<>(held.keySet());
        capacityIds.addAll(confirmed.keySet());

        for (String capacityId : capacityIds) {
            int heldQuantity = held.getOrDefault(capacityId, 0);
            int confirmedQuantity = confirmed.getOrDefault(capacityId, 0);

            if (heldQuantity > 0
                    && capacityRepository.releaseHeld(
                    eventId, capacityId, heldQuantity, now
            ) != 1) {
                throw counterMismatch();
            }

            if (confirmedQuantity > 0
                    && capacityRepository.releaseConfirmed(
                    eventId, capacityId, confirmedQuantity, now
            ) != 1) {
                throw counterMismatch();
            }
        }
    }

    /**
     * 저장된 상세와 실제 점유 카운터의 불일치를 표현한다.
     */
    private CustomException counterMismatch() {
        return new CustomException(
                ErrorCode.CAPACITY_COUNTER_MISMATCH
        );
    }
}