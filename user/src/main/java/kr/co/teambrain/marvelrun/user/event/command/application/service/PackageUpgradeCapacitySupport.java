package kr.co.teambrain.marvelrun.user.event.command.application.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.upgrade.PackageUpgradeStatus;
import kr.co.teambrain.marvelrun.user.event.command.repository.PackageUpgradeCommandRepository;
import kr.co.teambrain.marvelrun.common.json_object.PackageUpgradeHeldCapacity;
import kr.co.teambrain.marvelrun.common.json_object.ReservationHistoryEntry;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Capacity;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityRequirementDiff;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityRequirementInput;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.CapacityRequirementResolver;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.ReservationCapacityDiffService;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.ReservationHistoryRecorder;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.CapacityCommandRepository;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationCommandRepository;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationItemCommandRepository;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.PackageUpgrade;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 상향의 정원 계산과 held 확보·반환.
 *
 * 요청 때는 패키지 구성과 지금 예약 상세의 차이 가운데 늘어나는 행만 held로 잡는다.
 * 줄어드는 행(원래 단품 CATEGORY)은 승인 때 확정 수량에서 뺀다.
 * 잡은 held는 예약 상세에 넣지 않고 상향 대기에만 적는다.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class PackageUpgradeCapacitySupport {

    private final EntityManager entityManager;
    private final ReservationCommandRepository reservationRepository;
    private final CapacityRequirementResolver requirementResolver;
    private final ReservationCapacityDiffService diffService;
    private final CapacityCommandRepository capacityRepository;
    private final PackageUpgradeCommandRepository upgradeRepository;
    private final ReservationItemCommandRepository reservationItemRepository;
    private final ReservationHistoryRecorder historyRecorder;

    /** 신청의 확정 예약을 잠가 돌려준다. 상향은 결제를 마친(CONSUMED) 예약만 대상이다. */
    public Reservation lockConsumedReservation(Registration registration) {
        Reservation reservation = reservationRepository.findByRegistration_Id(registration.getId())
                .orElseThrow(() -> new CustomException(ErrorCode.RESERVATION_NOT_FOUND));
        entityManager.refresh(reservation, LockModeType.PESSIMISTIC_WRITE);
        if (reservation.getStatus() != ReservationStatus.CONSUMED) {
            throw new CustomException(ErrorCode.RESERVATION_STATE_CONFLICT,
                    " 상향은 결제를 마친 예약만 할 수 있습니다. reservationId=" + reservation.getId());
        }
        return reservation;
    }

    /**
     * 신청의 단품 줄 하나를 패키지로 바꿨을 때 필요한 정원과 지금 예약 상세의 차이.
     * 상향은 구매 줄이 하나뿐인 단품 신청만 하므로 그 줄의 패키지 구성이 신청 전체의 필요량이다.
     *
     * @param participants 상향 뒤 참가 항목 수(패키지 하위 종목 수). 전체 정원을 이만큼 쓴다.
     */
    public CapacityRequirementDiff packageDiff(
            RegistrationItem item,
            EventCategory packageCategory,
            int participants,
            Reservation reservation
    ) {
        Registration registration = item.getRegistration();
        String eventId = registration.getEvent().getId();
        CapacityRequirementInput input = CapacityRequirementInput.fromCandidate(
                packageCategory.getId(),
                registration.getBirth(),
                registration.getEvent().getStartDate().toLocalDate(),
                item.getSouvenirJson() == null ? List.of() : item.getSouvenirJson(),
                participants
        );
        Map<String, Integer> requirements = requirementResolver.resolveAll(eventId, List.of(input)).get(0);
        return diffService.compareAll(List.of(reservation), Map.of(reservation.getId(), requirements)).get(0);
    }

    /**
     * 상향 요청·취소처럼 예약 구성은 그대로 두고 계약금액·상태만 바꿀 때 예약 이력을 남긴다.
     * 지금 구성으로 MODIFY 이력을 붙이고(변경 전 신청 값), 변경을 적용한 뒤 변경 후 신청 값을 연결한다.
     */
    public void recordAmountChange(Reservation reservation, LocalDateTime now, String reason, Runnable change) {
        List<ReservationHistoryEntry.Item> composition = reservationItemRepository
                .findAllocations(List.of(reservation.getId())).stream()
                .map(allocation -> new ReservationHistoryEntry.Item(allocation.capacityId(), allocation.quantity()))
                .toList();
        historyRecorder.appendReservationHistorySnapshot(reservation, ReservationHistoryEntry.Action.MODIFY,
                now, null, reason, composition);
        change.run();
        historyRecorder.completeReservationModificationSnapshot(reservation);
    }

    /**
     * 승인 전에 정원 숫자로 승인 뒤 이동이 성공하는지 본다. 행은 Capacity ID 순으로 잠근다.
     * 늘어나는 행은 잡아 둔 held가, 줄어드는 행은 확정 수량이 모자라면 실패한다.
     */
    public void validatePreheldMove(String eventId, CapacityRequirementDiff diff, PackageUpgrade upgrade) {
        Map<String, Integer> held = heldQuantities(upgrade);
        Map<String, Integer> decreases = new TreeMap<>();
        for (CapacityRequirementDiff.Item item : diff.items()) {
            if (item.decrease() > 0) {
                decreases.put(item.capacityId(), item.decrease());
            }
        }
        TreeMap<String, Boolean> ids = new TreeMap<>();
        held.keySet().forEach(id -> ids.put(id, true));
        decreases.keySet().forEach(id -> ids.put(id, true));
        for (String capacityId : ids.keySet()) {
            Capacity capacity = entityManager.find(Capacity.class, capacityId);
            if (capacity != null) {
                entityManager.refresh(capacity, LockModeType.PESSIMISTIC_WRITE);
            }
            boolean valid = capacity != null
                    && capacity.getEvent() != null && eventId.equals(capacity.getEvent().getId())
                    && capacity.getHeldCount() >= held.getOrDefault(capacityId, 0)
                    && capacity.getConfirmedCount() >= decreases.getOrDefault(capacityId, 0);
            if (!valid) {
                throw new CustomException(ErrorCode.CAPACITY_COUNTER_MISMATCH,
                        " 상향 승인 전 정원 수량이 맞지 않습니다. upgradeId=" + upgrade.getId() + ", capacityId=" + capacityId);
            }
        }
    }

    /** 차이 가운데 늘어나는 행과 수량. */
    public static Map<String, Integer> increases(CapacityRequirementDiff diff) {
        Map<String, Integer> result = new TreeMap<>();
        for (CapacityRequirementDiff.Item item : diff.items()) {
            if (item.increase() > 0) {
                result.put(item.capacityId(), item.increase());
            }
        }
        return result;
    }

    /** 상향 대기가 잡아 둔 held 행과 수량. */
    public static Map<String, Integer> heldQuantities(PackageUpgrade upgrade) {
        Map<String, Integer> result = new TreeMap<>();
        for (PackageUpgradeHeldCapacity held : upgrade.getHeldCapacities()) {
            result.merge(held.capacityId(), held.quantity(), Integer::sum);
        }
        return result;
    }

    /** 늘어나는 행만 held로 잡는다. 하나라도 모자라면 정원 부족으로 끝낸다(트랜잭션 전체가 롤백된다). */
    public List<PackageUpgradeHeldCapacity> holdIncreases(
            String eventId,
            CapacityRequirementDiff diff,
            LocalDateTime now
    ) {
        List<PackageUpgradeHeldCapacity> held = new ArrayList<>();
        for (CapacityRequirementDiff.Item item : diff.items()) {
            if (item.increase() <= 0) {
                continue;
            }
            if (capacityRepository.acquireHeld(eventId, item.capacityId(), item.increase(), now) != 1) {
                throw new CustomException(ErrorCode.CAPACITY_ACQUIRE_FAILED,
                        " 상향 정원 확보 실패. capacityId=" + item.capacityId());
            }
            held.add(new PackageUpgradeHeldCapacity(item.capacityId(), item.increase()));
        }
        if (held.isEmpty()) {
            throw new CustomException(ErrorCode.CAPACITY_CONFIGURATION_ERROR,
                    " 패키지 구성과 단품 구성의 정원 차이가 없습니다. registrationId=" + diff.reservationId());
        }
        return held;
    }

    /**
     * 신청을 취소할 때 남은 상향 대기도 함께 정리한다. 예약 반환(releaseAll)은 예약 상세만 돌려주므로
     * 상향이 따로 잡아 둔 held는 여기서 돌려준다. READY 상향 주문은 호출 흐름이 이미 무효로 한다.
     */
    public void cancelPendingForCanceledRegistrations(List<String> registrationIds, LocalDateTime now) {
        if (registrationIds.isEmpty()) {
            return;
        }
        for (PackageUpgrade upgrade : upgradeRepository.findAllByRegistrationsForUpdate(
                registrationIds, PackageUpgradeStatus.PENDING)) {
            releaseHeld(upgrade, now);
            upgrade.cancel(now);
        }
    }

    /** 상향 대기가 잡아 둔 held를 돌려준다. 수량이 맞지 않으면 숫자를 임의로 맞추지 않고 실패시킨다. */
    public void releaseHeld(PackageUpgrade upgrade, LocalDateTime now) {
        String eventId = upgrade.getEvent().getId();
        for (PackageUpgradeHeldCapacity held : upgrade.getHeldCapacities()) {
            if (capacityRepository.releaseHeld(eventId, held.capacityId(), held.quantity(), now) != 1) {
                throw new CustomException(ErrorCode.CAPACITY_COUNTER_MISMATCH,
                        " 상향 held 반환 실패. upgradeId=" + upgrade.getId() + ", capacityId=" + held.capacityId());
            }
        }
    }
}
