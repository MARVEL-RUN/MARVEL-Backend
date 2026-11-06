package kr.co.teambrain.marvelrun.user.event.command.application.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.upgrade.PackageUpgradeStatus;
import kr.co.teambrain.marvelrun.user.event.command.repository.PackageUpgradeCommandRepository;
import kr.co.teambrain.marvelrun.common.json_object.PackageUpgradeHeldCapacity;
import kr.co.teambrain.marvelrun.common.json_object.ReservationHistoryEntry;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
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
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategorySouvenir;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.PackageUpgrade;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationItem;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Souvenir;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategorySouvenirCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

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

    private static final String FREE_SIZE = "FREE";

    private final EntityManager entityManager;
    private final ReservationCommandRepository reservationRepository;
    private final CapacityRequirementResolver requirementResolver;
    private final ReservationCapacityDiffService diffService;
    private final CapacityCommandRepository capacityRepository;
    private final PackageUpgradeCommandRepository upgradeRepository;
    private final ReservationItemCommandRepository reservationItemRepository;
    private final ReservationHistoryRecorder historyRecorder;
    private final EventCategorySouvenirCommandRepository souvenirMappingRepository;

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
     * 기념품은 패키지 종목에 연결된 것 전부(packageSouvenirs)라서 단품에 없던 부문 키트가 늘어나는 행이 된다.
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
        // 상품은 어린이 정원을 쓰지 않는다(신청 때 상품 줄 확보와 같은 규칙).
        CapacityRequirementInput input = new CapacityRequirementInput(
                packageCategory.getId(),
                false,
                packageSouvenirs(item, packageCategory),
                participants
        );
        Map<String, Integer> requirements = requirementResolver.resolveAll(eventId, List.of(input)).get(0);
        return diffService.compareAll(List.of(reservation), Map.of(reservation.getId(), requirements)).get(0);
    }

    /**
     * 상향 뒤 그 줄이 받을 기념품. 패키지 종목에 연결된 기념품마다 하나씩이며 기념품 순서대로 담는다.
     * 단품 줄에서 이미 고른 기념품은 그 선택을 그대로 둔다. 새로 받는 기념품은 사이즈를 고르지 않는 것(FREE)이어야 한다.
     * 상향 요청에는 기념품 선택을 받지 않으므로, 사이즈를 골라야 하는 기념품이 패키지에 연결돼 있으면 설정 오류로 막는다.
     * 단품 줄이 받은 기념품이 패키지에 연결돼 있지 않아도 설정 오류다. 그대로 두면 그 기념품이 상향 뒤 빠지고 재고도 돌아간다.
     */
    public List<SouvenirJson> packageSouvenirs(RegistrationItem item, EventCategory packageCategory) {
        Map<String, SouvenirJson> selected = new HashMap<>();
        if (item.getSouvenirJson() != null) {
            for (SouvenirJson souvenir : item.getSouvenirJson()) {
                selected.put(souvenir.souvenirId(), souvenir);
            }
        }
        List<EventCategorySouvenir> mappings = new ArrayList<>(
                souvenirMappingRepository.findAllMappingsByCategoryId(packageCategory.getId()));
        mappings.sort(Comparator.comparing((EventCategorySouvenir mapping) -> mapping.getSouvenir().getOrder())
                .thenComparing(mapping -> mapping.getSouvenir().getId()));

        List<SouvenirJson> result = new ArrayList<>(mappings.size());
        for (EventCategorySouvenir mapping : mappings) {
            Souvenir souvenir = mapping.getSouvenir();
            SouvenirJson kept = selected.get(souvenir.getId());
            if (kept != null) {
                result.add(kept);
                continue;
            }
            if (!Boolean.TRUE.equals(souvenir.getIsActive())) {
                throw new CustomException(ErrorCode.SOUVENIR_NOT_ACTIVE,
                        " 상향으로 더할 기념품이 비활성입니다. souvenirId=" + souvenir.getId());
            }
            if (!isFreeSizeOnly(souvenir.getSizes())) {
                throw new CustomException(ErrorCode.REGISTRATION_POLICY_CONFIGURATION_ERROR,
                        " 상향으로 더할 기념품은 사이즈를 고르지 않는 것이어야 합니다. souvenirId=" + souvenir.getId());
            }
            result.add(new SouvenirJson(souvenir.getId(), FREE_SIZE));
        }
        Set<String> linked = mappings.stream().map(mapping -> mapping.getSouvenir().getId()).collect(Collectors.toSet());
        if (!linked.containsAll(selected.keySet())) {
            throw new CustomException(ErrorCode.REGISTRATION_POLICY_CONFIGURATION_ERROR,
                    " 단품 줄의 기념품이 패키지에 연결돼 있지 않습니다. itemId=" + item.getId()
                            + ", packageId=" + packageCategory.getId());
        }
        return List.copyOf(result);
    }

    /** 사이즈 목록이 비었거나 FREE 하나뿐이다(신청 검증의 사이즈 해석과 같다). */
    private static boolean isFreeSizeOnly(String sizes) {
        if (sizes == null || sizes.isBlank()) {
            return true;
        }
        Set<String> allowed = Arrays.stream(sizes.split("\\|"))
                .map(String::trim)
                .filter(size -> !size.isBlank())
                .collect(Collectors.toSet());
        return allowed.equals(Set.of(FREE_SIZE));
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
