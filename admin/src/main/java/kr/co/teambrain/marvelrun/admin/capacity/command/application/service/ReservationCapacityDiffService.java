package kr.co.teambrain.marvelrun.admin.capacity.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.dto.CapacityRequirementDiff;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.dto.ReservationAllocation;
import kr.co.teambrain.marvelrun.admin.capacity.command.repository.ReservationItemCommandRepository;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 기존 예약 상세와 검증된 후보의 필요량을 비교한다.
 *
 * 기존 수량은 현재 종목 설정으로 재계산하지 않는다.
 * ReservationItem에서 읽은 실제 확보 구성을 기준으로 사용한다.
 *
 * 카운터·예약·신청을 변경하지 않으며 실제 이동은 다음 단계가 담당한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ReservationCapacityDiffService {

    private final ReservationItemCommandRepository reservationItemRepository;

    /**
     * 기존 예약들의 상세를 한 번에 조회하여 예약별 차이를 계산한다.
     *
     * newRequirementsByReservationId에는 각 예약의 수정 후 전체 필요량을 전달한다.
     * 신규 참가자의 최초 확보와 제거 대상의 반환은 이 메서드의 책임이 아니다.
     *
     * 호출자는 수정 대상의 소유권을 검증하고 동일 수정 트랜잭션에서 사용해야 한다.
     */
    public List<CapacityRequirementDiff> compareAll(
            List<Reservation> reservations,
            Map<String, Map<String, Integer>> newRequirementsByReservationId
    ) {
        Map<String, Reservation> reservationById =
                new TreeMap<>();

        for (Reservation reservation : reservations) {
            if (reservation.getId() == null
                    || reservationById.putIfAbsent(
                    reservation.getId(),
                    reservation
            ) != null) {
                throw invalidArgument(" 예약 식별자가 없거나 중복되었습니다.");
            }

            if (reservation.getStatus() != ReservationStatus.HELD
                    && reservation.getStatus() != ReservationStatus.CONSUMED) {
                throw stateConflict(
                        " 현재 점유량을 이동할 수 있는 예약 상태가 아닙니다."
                                + " reservationId=" + reservation.getId()
                );
            }
        }

        if (!reservationById.keySet().equals(
                newRequirementsByReservationId.keySet()
        )) {
            throw invalidArgument(" 예약 목록과 새 필요량의 대상이 일치하지 않습니다.");
        }

        if (reservationById.isEmpty()) {
            return List.of();
        }

        Map<String, Map<String, Integer>> oldByReservation =
                new TreeMap<>();

        for (String reservationId : reservationById.keySet()) {
            validateNewRequirements(
                    newRequirementsByReservationId.get(reservationId)
            );

            oldByReservation.put(
                    reservationId,
                    new TreeMap<>()
            );
        }

        List<ReservationAllocation> allocations =
                reservationItemRepository.findAllocations(
                        reservationById.keySet()
                );

        for (ReservationAllocation allocation : allocations) {
            Map<String, Integer> oldQuantities =
                    oldByReservation.get(allocation.reservationId());

            if (oldQuantities == null
                    || allocation.capacityId() == null
                    || allocation.capacityId().isBlank()
                    || allocation.quantity() <= 0) {
                throw stateConflict(" 예약 상세의 대상 또는 수량이 올바르지 않습니다.");
            }

            if (oldQuantities.putIfAbsent(
                    allocation.capacityId(),
                    allocation.quantity()
            ) != null) {
                throw stateConflict(
                        " 같은 예약의 Capacity 상세가 중복되었습니다."
                );
            }
        }

        List<CapacityRequirementDiff> results =
                new ArrayList<>(reservationById.size());

        for (Reservation reservation : reservationById.values()) {
            Map<String, Integer> oldQuantities =
                    oldByReservation.get(reservation.getId());

            if (oldQuantities.isEmpty()) {
                throw stateConflict(
                        " 점유 중인 예약의 상세가 누락되었습니다."
                                + " reservationId=" + reservation.getId()
                );
            }

            results.add(
                    new CapacityRequirementDiff(
                            reservation.getId(),
                            reservation.getStatus(),
                            reservation.getVersion(),
                            calculateItems(
                                    oldQuantities,
                                    newRequirementsByReservationId.get(
                                            reservation.getId()
                                    )
                            )
                    )
            );
        }

        return List.copyOf(results);
    }

    /**
     * 기존 예약을 유지·수정하는 데 필요한 새 수량 입력을 확인한다.
     *
     * 전체 제거를 빈 Map으로 표현하지 않으며 제거는 별도 흐름에서 처리한다.
     */
    private void validateNewRequirements(
            Map<String, Integer> quantities
    ) {
        if (quantities == null || quantities.isEmpty()) {
            throw invalidArgument(" 수정 후 필요량이 비어 있습니다.");
        }

        for (Map.Entry<String, Integer> entry : quantities.entrySet()) {
            if (entry.getKey() == null
                    || entry.getKey().isBlank()
                    || entry.getValue() == null
                    || entry.getValue() <= 0) {
                throw invalidArgument(" 수정 후 필요량에 잘못된 값이 있습니다.");
            }
        }
    }

    /**
     * Capacity ID 합집합을 기준으로 증가·감소·공통 수량을 계산한다.
     *
     * 기존과 새 구성에 공통으로 존재하는 수량은 추가 확보하지 않는다.
     */
    private List<CapacityRequirementDiff.Item> calculateItems(
            Map<String, Integer> oldQuantities,
            Map<String, Integer> newQuantities
    ) {
        TreeSet<String> capacityIds =
                new TreeSet<>(oldQuantities.keySet());

        capacityIds.addAll(newQuantities.keySet());

        List<CapacityRequirementDiff.Item> results =
                new ArrayList<>(capacityIds.size());

        for (String capacityId : capacityIds) {
            int oldQuantity =
                    oldQuantities.getOrDefault(capacityId, 0);

            int newQuantity =
                    newQuantities.getOrDefault(capacityId, 0);

            results.add(
                    new CapacityRequirementDiff.Item(
                            capacityId,
                            oldQuantity,
                            newQuantity,
                            Math.max(newQuantity - oldQuantity, 0),
                            Math.max(oldQuantity - newQuantity, 0),
                            Math.min(oldQuantity, newQuantity)
                    )
            );
        }

        return List.copyOf(results);
    }

    /**
     * 호출자가 전달한 예약·필요량 입력 불일치를 표현한다.
     */
    private CustomException invalidArgument(String detail) {
        return new CustomException(
                ErrorCode.INVALID_RESERVATION_ARGUMENT,
                detail
        );
    }

    /**
     * 예약 상태 또는 저장된 확보 상세의 불일치를 표현한다.
     */
    private CustomException stateConflict(String detail) {
        return new CustomException(
                ErrorCode.RESERVATION_STATE_CONFLICT,
                detail
        );
    }
}