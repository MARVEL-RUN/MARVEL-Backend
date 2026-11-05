package kr.co.teambrain.marvelrun.user.event.command.application.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.json_object.PackageUpgradeHeldCapacity;
import kr.co.teambrain.marvelrun.common.json_object.ReservationHistoryEntry;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Capacity;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityRequirementDiff;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.ReservationAllocation;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.CapacityRequirementResolver;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.ReservationCapacityDiffService;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.ReservationHistoryRecorder;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.CapacityCommandRepository;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationCommandRepository;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationItemCommandRepository;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.PackageUpgrade;
import kr.co.teambrain.marvelrun.user.event.command.repository.PackageUpgradeCommandRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** 상향의 승인 전 정원 숫자 검사와 계약금액 변경 이력을 본다. */
@ExtendWith(MockitoExtension.class)
class PackageUpgradeCapacitySupportTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 10, 0);

    @Mock private EntityManager entityManager;
    @Mock private ReservationCommandRepository reservationRepository;
    @Mock private CapacityRequirementResolver requirementResolver;
    @Mock private ReservationCapacityDiffService diffService;
    @Mock private CapacityCommandRepository capacityRepository;
    @Mock private PackageUpgradeCommandRepository upgradeRepository;
    @Mock private ReservationItemCommandRepository reservationItemRepository;
    @Mock private ReservationHistoryRecorder historyRecorder;
    @Mock private PackageUpgrade upgrade;

    private final Event event = Event.builder().id("event").build();

    private PackageUpgradeCapacitySupport support() {
        return new PackageUpgradeCapacitySupport(entityManager, reservationRepository, requirementResolver, diffService,
                capacityRepository, upgradeRepository, reservationItemRepository, historyRecorder);
    }

    /** 잡아 둔 held와 돌려줄 확정 수량이 충분하면 통과하고, 행은 Capacity ID 순으로 잠근다. */
    @Test
    void passesWhenCountersCoverMove() {
        when(upgrade.getHeldCapacities()).thenReturn(List.of(new PackageUpgradeHeldCapacity("cap-b-package", 1)));
        Capacity single = capacity("cap-a-single", 0, 1);
        Capacity pkg = capacity("cap-b-package", 1, 0);

        assertThatCode(() -> support().validatePreheldMove("event", diff(), upgrade)).doesNotThrowAnyException();

        InOrder order = inOrder(entityManager);
        order.verify(entityManager).refresh(single, LockModeType.PESSIMISTIC_WRITE);
        order.verify(entityManager).refresh(pkg, LockModeType.PESSIMISTIC_WRITE);
    }

    /** 잡아 둔 held가 모자라면 승인 뒤 확정 이동이 0행이 되므로 승인 전에 막는다. */
    @Test
    void rejectsShortHeld() {
        when(upgrade.getHeldCapacities()).thenReturn(List.of(new PackageUpgradeHeldCapacity("cap-b-package", 1)));
        capacity("cap-a-single", 0, 1);
        capacity("cap-b-package", 0, 0);

        assertThatThrownBy(() -> support().validatePreheldMove("event", diff(), upgrade))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CAPACITY_COUNTER_MISMATCH);
    }

    /** 돌려줄 원래 단품의 확정 수량이 모자라도 막는다. */
    @Test
    void rejectsShortConfirmedForDecrease() {
        when(upgrade.getHeldCapacities()).thenReturn(List.of(new PackageUpgradeHeldCapacity("cap-b-package", 1)));
        capacity("cap-a-single", 0, 0);

        assertThatThrownBy(() -> support().validatePreheldMove("event", diff(), upgrade))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CAPACITY_COUNTER_MISMATCH);
    }

    /** 계약금액 변경은 지금 예약 구성 그대로 MODIFY 이력을 붙이고, 변경 뒤 신청 값을 연결한다. */
    @Test
    void recordsAmountChangeWithCurrentComposition() {
        Reservation reservation = mock(Reservation.class);
        when(reservation.getId()).thenReturn("reservation");
        when(reservationItemRepository.findAllocations(List.of("reservation")))
                .thenReturn(List.of(new ReservationAllocation("reservation", "cap-a-single", 1)));
        Runnable change = mock(Runnable.class);

        support().recordAmountChange(reservation, NOW, "사유", change);

        InOrder order = inOrder(historyRecorder, change);
        order.verify(historyRecorder).appendReservationHistorySnapshot(reservation, ReservationHistoryEntry.Action.MODIFY,
                NOW, null, "사유", List.of(new ReservationHistoryEntry.Item("cap-a-single", 1)));
        order.verify(change).run();
        order.verify(historyRecorder).completeReservationModificationSnapshot(reservation);
    }

    /** 원래 단품 CATEGORY는 1 줄고 패키지 세트는 1 는다. */
    private static CapacityRequirementDiff diff() {
        return new CapacityRequirementDiff("reservation", ReservationStatus.CONSUMED, 1L, List.of(
                new CapacityRequirementDiff.Item("cap-a-single", 1, 0, 0, 1, 0),
                new CapacityRequirementDiff.Item("cap-b-package", 0, 1, 1, 0, 0)));
    }

    private Capacity capacity(String id, int held, int confirmed) {
        Capacity capacity = mock(Capacity.class);
        when(capacity.getEvent()).thenReturn(event);
        lenient().when(capacity.getHeldCount()).thenReturn(held);
        lenient().when(capacity.getConfirmedCount()).thenReturn(confirmed);
        when(entityManager.find(Capacity.class, id)).thenReturn(capacity);
        return capacity;
    }
}
