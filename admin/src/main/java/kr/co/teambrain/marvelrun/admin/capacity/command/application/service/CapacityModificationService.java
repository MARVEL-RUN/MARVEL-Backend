package kr.co.teambrain.marvelrun.admin.capacity.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.json_object.ReservationHistoryEntry;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.domain.ReservationItem;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.dto.CapacityRequirementDiff;
import kr.co.teambrain.marvelrun.admin.capacity.command.repository.CapacityCommandRepository;
import kr.co.teambrain.marvelrun.admin.capacity.command.repository.ReservationCommandRepository;
import kr.co.teambrain.marvelrun.admin.capacity.command.repository.ReservationItemCommandRepository;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 기존 예약의 증가분 확보, 감소분 반환 및 현재 상세 교체를 수행한다.
 *
 * HELD는 heldCount, CONSUMED는 confirmedCount를 변경한다.
 * 예약 상태와 확보 회차는 유지한다.
 *
 * 호출자는 소유권·정책·가격·수정 가능 상태를 검증하고,
 * 같은 트랜잭션에서 생성한 Diff를 전달해야 한다.
 *
 * 이 서비스는 수정 권한이나 결제·환불 가능 여부를 판정하지 않는다.
 * 실패 시 호출 트랜잭션 전체가 롤백되어야 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class CapacityModificationService {

    private final CapacityCommandRepository capacityRepository;
    private final ReservationCommandRepository reservationRepository;
    private final ReservationItemCommandRepository reservationItemRepository;
    private final ReservationCapacityDiffService reservationCapacityDiffService;

    /**
     * 기존 예약들의 자원 구성을 같은 트랜잭션에서 일괄 변경한다.
     *
     * 신규 참가자의 최초 확보와 제거 대상의 반환은 별도 흐름에서 처리한다.
     * 빈 목록은 기존 예약 수정 대상이 없는 경우로 보고 아무 작업도 하지 않는다.
     *
     * 예약별 변경 이력을 먼저 flush하여 @Version 충돌을 확인한다.
     * 이후 실패하면 먼저 저장한 이력과 버전 증가도 함께 롤백된다.
     */
    public void moveAll(
            String eventId,
            List<CapacityRequirementDiff> diffs,
            LocalDateTime now
    ) {
        if (eventId == null || eventId.isBlank()
                || diffs == null || now == null) {
            throw invalidArgument(" 자원 이동 요청의 필수 값이 없습니다.");
        }

        if (diffs.isEmpty()) {
            return;
        }

        Map<String, CapacityRequirementDiff> diffByReservation =
                new TreeMap<>();

        Map<String, Map<String, Integer>> newRequirements =
                new TreeMap<>();

        for (CapacityRequirementDiff diff : diffs) {
            if (diff == null
                    || diff.reservationId() == null
                    || diff.reservationId().isBlank()
                    || diff.reservationVersion() == null) {
                throw invalidArgument(" 예약 식별자 또는 버전이 없습니다.");
            }

            if (diffByReservation.putIfAbsent(
                    diff.reservationId(), diff
            ) != null) {
                throw invalidArgument(" 자원 이동 대상 예약이 중복되었습니다.");
            }

            newRequirements.put(
                    diff.reservationId(),
                    extractNewRequirements(diff)
            );
        }

        List<Reservation> reservations =
                new ArrayList<>(diffByReservation.size());

        /*
         * 예약 ID 순서로 실제 UPDATE와 flush를 수행한다.
         * 단순 Java 버전 비교만으로 동시 변경을 막았다고 간주하지 않는다.
         */
        for (CapacityRequirementDiff diff : diffByReservation.values()) {
            Reservation reservation =
                    reservationRepository.findById(diff.reservationId())
                            .orElseThrow(
                                    () -> new CustomException(
                                            ErrorCode.RESERVATION_NOT_FOUND
                                    )
                            );

            validateReservation(eventId, reservation, diff);

            List<ReservationHistoryEntry.Item> historyItems =
                    newRequirements.get(reservation.getId())
                            .entrySet().stream()
                            .map(entry -> new ReservationHistoryEntry.Item(
                                    entry.getKey(),
                                    entry.getValue()
                            ))
                            .toList();

            reservation.appendHistory(
                    ReservationHistoryEntry.Action.MODIFY,
                    now,
                    null,
                    "신청 수정에 따른 자원 구성 변경",
                    historyItems
            );

            /*
             * 이력 변경으로 Reservation UPDATE가 실행되고
             * @Version 조건이 검증된다.
             *
             * 동시 결제 시작·반환·수정이 먼저 반영되었다면 실패한다.
             * 성공한 UPDATE의 행 잠금은 트랜잭션 종료까지 유지된다.
             */
            reservationRepository.flush();

            reservations.add(reservation);
        }

        /*
         * 예약 변경권을 확보한 뒤 실제 상세와 Diff를 다시 대조한다.
         * 수동으로 잘못 구성된 Diff나 오래된 상세 기준의 이동을 막는다.
         */
        List<CapacityRequirementDiff> verifiedDiffs =
                reservationCapacityDiffService.compareAll(
                        reservations,
                        newRequirements
                );

        for (CapacityRequirementDiff verified : verifiedDiffs) {
            CapacityRequirementDiff supplied =
                    diffByReservation.get(verified.reservationId());

            if (!verified.items().equals(supplied.items())) {
                throw stateConflict(
                        " 현재 예약 상세와 자원 변경 계산이 일치하지 않습니다."
                                + " reservationId=" + verified.reservationId()
                );
            }
        }

        /*
         * Capacity ID별로 모으되 HELD / CONSUMED 및 증가 / 감소는
         * 서로 상쇄하지 않고 분리한다.
         */
        Map<String, CounterDelta> deltas = new TreeMap<>();

        for (CapacityRequirementDiff diff : verifiedDiffs) {
            boolean held =
                    diff.reservationStatus() == ReservationStatus.HELD;

            for (CapacityRequirementDiff.Item item : diff.items()) {
                if (item.increase() == 0 && item.decrease() == 0) {
                    continue;
                }

                CounterDelta delta =
                        deltas.computeIfAbsent(
                                item.capacityId(),
                                ignored -> new CounterDelta()
                        );

                delta.add(held, item.increase(), item.decrease());
            }
        }

        // 모든 증가분 확보에 성공한 뒤에만 감소분을 반환한다.
        for (Map.Entry<String, CounterDelta> entry : deltas.entrySet()) {
            acquireIncreases(
                    eventId,
                    entry.getKey(),
                    entry.getValue(),
                    now
            );
        }

        for (Map.Entry<String, CounterDelta> entry : deltas.entrySet()) {
            releaseDecreases(
                    eventId,
                    entry.getKey(),
                    entry.getValue(),
                    now
            );
        }

        replaceItems(reservations, newRequirements);

        reservationItemRepository.flush();
    }

    /**
     * 전달된 Diff의 기본 형식을 검증하고 수정 후 전체 필요량을 추출한다.
     *
     * old/new와 증가·감소·공통 수량의 일치는 실제 상세 재대조에서 확인한다.
     */
    private Map<String, Integer> extractNewRequirements(
            CapacityRequirementDiff diff
    ) {
        Map<String, Integer> result = new TreeMap<>();
        Map<String, CapacityRequirementDiff.Item> seen = new TreeMap<>();

        for (CapacityRequirementDiff.Item item : diff.items()) {
            if (item.capacityId() == null
                    || item.capacityId().isBlank()
                    || item.oldQuantity() < 0
                    || item.newQuantity() < 0
                    || item.increase() < 0
                    || item.decrease() < 0
                    || item.unchanged() < 0) {
                throw invalidArgument(" 자원 변경 수량 또는 식별자가 올바르지 않습니다.");
            }

            if (seen.putIfAbsent(item.capacityId(), item) != null) {
                throw invalidArgument(" 같은 예약의 Capacity가 중복되었습니다.");
            }

            if (item.newQuantity() > 0) {
                result.put(item.capacityId(), item.newQuantity());
            }
        }

        if (result.isEmpty()) {
            throw invalidArgument(
                    " 수정 후 필요량이 비어 있습니다. 전체 제거는 별도 처리해야 합니다."
            );
        }

        return result;
    }

    /**
     * 예약의 대회 귀속과 Diff 계산 당시 상태·버전이 일치하는지 확인한다.
     *
     * 실제 동시 변경 검증은 이어지는 Reservation UPDATE에서 수행한다.
     */
    private void validateReservation(
            String eventId,
            Reservation reservation,
            CapacityRequirementDiff diff
    ) {
        if (!eventId.equals(
                reservation.getRegistration().getEvent().getId()
        )) {
            throw invalidArgument(" 자원 이동 대상 예약의 대회가 일치하지 않습니다.");
        }

        if (reservation.getStatus() != ReservationStatus.HELD
                && reservation.getStatus() != ReservationStatus.CONSUMED) {
            throw stateConflict(" 현재 상태의 예약은 자원을 이동할 수 없습니다.");
        }

        if (reservation.getStatus() != diff.reservationStatus()
                || !Objects.equals(
                reservation.getVersion(),
                diff.reservationVersion()
        )) {
            throw stateConflict(
                    " 자원 변경 계산 이후 예약 상태 또는 버전이 변경되었습니다."
                            + " reservationId=" + reservation.getId()
            );
        }
    }

    /**
     * 같은 Capacity의 홀딩 증가분과 확정 증가분을 각각 확보한다.
     *
     * 어느 한쪽이라도 실패하면 이전 확보를 포함하여 전체 롤백한다.
     */
    private void acquireIncreases(
            String eventId,
            String capacityId,
            CounterDelta delta,
            LocalDateTime now
    ) {
        if (delta.heldIncrease > 0
                && capacityRepository.acquireHeld(
                eventId, capacityId, delta.heldIncrease, now
        ) != 1) {
            throw new CustomException(ErrorCode.CAPACITY_ACQUIRE_FAILED);
        }

        if (delta.confirmedIncrease > 0
                && capacityRepository.acquireConfirmed(
                eventId, capacityId, delta.confirmedIncrease, now
        ) != 1) {
            throw new CustomException(ErrorCode.CAPACITY_ACQUIRE_FAILED);
        }
    }

    /**
     * 증가분 확보를 모두 마친 뒤 불필요해진 기존 점유량을 반환한다.
     *
     * 실제 카운터가 반환할 수량보다 작으면 데이터 불일치로 차단한다.
     */
    private void releaseDecreases(
            String eventId,
            String capacityId,
            CounterDelta delta,
            LocalDateTime now
    ) {
        if (delta.heldDecrease > 0
                && capacityRepository.releaseHeld(
                eventId, capacityId, delta.heldDecrease, now
        ) != 1) {
            throw new CustomException(ErrorCode.CAPACITY_COUNTER_MISMATCH);
        }

        if (delta.confirmedDecrease > 0
                && capacityRepository.releaseConfirmed(
                eventId, capacityId, delta.confirmedDecrease, now
        ) != 1) {
            throw new CustomException(ErrorCode.CAPACITY_COUNTER_MISMATCH);
        }
    }

    /**
     * 현재 확보 상세를 수정 후 전체 필요량으로 교체한다.
     *
     * 기존 구성은 Reservation의 이전 이력에 남아 있다.
     * 벌크 삭제 후 기존 ReservationItem 엔티티를 재사용하지 않는다.
     */
    private void replaceItems(
            List<Reservation> reservations,
            Map<String, Map<String, Integer>> newRequirements
    ) {
        List<String> reservationIds =
                reservations.stream()
                        .map(Reservation::getId)
                        .toList();

        reservationItemRepository.deleteAllByReservationIds(reservationIds);

        for (Reservation reservation : reservations) {
            for (Map.Entry<String, Integer> entry :
                    newRequirements.get(reservation.getId()).entrySet()) {

                reservationItemRepository.save(
                        ReservationItem.create(
                                reservation,
                                capacityRepository.getReferenceById(
                                        entry.getKey()
                                ),
                                entry.getValue()
                        )
                );
            }
        }
    }

    /**
     * 호출자가 전달한 대상 또는 수량의 형식 오류를 표현한다.
     */
    private CustomException invalidArgument(String detail) {
        return new CustomException(
                ErrorCode.INVALID_RESERVATION_ARGUMENT,
                detail
        );
    }

    /**
     * 예약 상태·버전·상세와 변경 요청 사이의 충돌을 표현한다.
     */
    private CustomException stateConflict(String detail) {
        return new CustomException(
                ErrorCode.RESERVATION_STATE_CONFLICT,
                detail
        );
    }

    /**
     * Capacity 한 개에 적용할 카운터별 증가·감소 수량을 보관한다.
     *
     * 다른 예약에서 발생한 감소분으로 증가분을 상쇄하지 않는다.
     */
    private static final class CounterDelta {

        private int heldIncrease;
        private int heldDecrease;
        private int confirmedIncrease;
        private int confirmedDecrease;

        /**
         * 예약 상태에 대응하는 수량에 변경분을 합산한다.
         */
        private void add(
                boolean held,
                int increase,
                int decrease
        ) {
            if (held) {
                heldIncrease = Math.addExact(heldIncrease, increase);
                heldDecrease = Math.addExact(heldDecrease, decrease);
            } else {
                confirmedIncrease =
                        Math.addExact(confirmedIncrease, increase);

                confirmedDecrease =
                        Math.addExact(confirmedDecrease, decrease);
            }
        }
    }
}