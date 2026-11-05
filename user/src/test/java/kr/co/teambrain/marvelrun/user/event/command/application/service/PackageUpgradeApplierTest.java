package kr.co.teambrain.marvelrun.user.event.command.application.service;

import jakarta.persistence.EntityManager;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ProductType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;
import kr.co.teambrain.marvelrun.common.inheritance_enum.upgrade.PackageUpgradeStatus;
import kr.co.teambrain.marvelrun.common.json_object.PackageUpgradeEntry;
import kr.co.teambrain.marvelrun.common.json_object.PackageUpgradeHeldCapacity;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityRequirementDiff;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.CapacityModificationService;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.ReservationHistoryRecorder;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.PackageUpgrade;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationEntry;
import kr.co.teambrain.marvelrun.user.event.command.repository.PackageUpgradeCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationEntryCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.PaymentAllocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 결제 승인 때 상향을 적용하는 순서와 상향이 아닌 결제를 건드리지 않는지 본다. */
@ExtendWith(MockitoExtension.class)
class PackageUpgradeApplierTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 10, 0);
    private static final BigDecimal DIFF = new BigDecimal("35000");

    @Mock private EntityManager entityManager;
    @Mock private PackageUpgradeCommandRepository upgradeRepository;
    @Mock private RegistrationEntryCommandRepository entryRepository;
    @Mock private PackageUpgradeCapacitySupport capacitySupport;
    @Mock private CapacityModificationService capacityModificationService;
    @Mock private ReservationHistoryRecorder historyRecorder;
    @Mock private Payment payment;
    @Mock private PaymentAllocation allocation;

    private final Event event = Event.builder().id("event").build();
    private final EventCategory pkg = EventCategory.builder().id("package").event(event)
            .productType(ProductType.PACKAGE).amount(new BigDecimal("75000")).build();
    private final EventCategory avengers = single("avengers");
    private final EventCategory xmen = single("xmen");

    private PackageUpgradeApplier applier() {
        return new PackageUpgradeApplier(entityManager, upgradeRepository, entryRepository, capacitySupport,
                capacityModificationService, historyRecorder);
    }

    /** 최초 결제나 단체 결제는 상향 대기를 찾지도 않는다. */
    @Test
    void ignoresNonAdditionalPayment() {
        when(payment.isRegistrationPayment()).thenReturn(true);
        when(payment.getPurpose()).thenReturn(PaymentPurpose.REGISTRATION_TRY);

        applier().applyIfPending(payment, List.of(allocation), NOW);

        verifyNoInteractions(upgradeRepository, capacitySupport, capacityModificationService, entryRepository);
    }

    /** 상향 대기가 없는 추가 결제는 그대로 둔다. */
    @Test
    void ignoresAdditionalPaymentWithoutPendingUpgrade() {
        Registration registration = upgrading();
        additionalPayment(registration);
        when(upgradeRepository.findByRegistrationForUpdate("registration", PackageUpgradeStatus.PENDING))
                .thenReturn(Optional.empty());

        applier().applyIfPending(payment, List.of(allocation), NOW);

        verifyNoInteractions(capacitySupport, capacityModificationService, entryRepository);
        assertThat(registration.getEventCategory()).isSameAs(avengers);
    }

    /** 승인 금액이 상향 차액과 다르면 Toss 호출 전에 막는다. */
    @Test
    void rejectsMismatchedAmountBeforeConfirm() {
        Registration registration = upgrading();
        additionalPayment(registration);
        when(allocation.getAllocatedAmount()).thenReturn(new BigDecimal("1000"));
        PackageUpgrade upgrade = upgrade(registration);
        when(upgradeRepository.findByRegistrationForUpdate("registration", PackageUpgradeStatus.PENDING))
                .thenReturn(Optional.of(upgrade));

        assertThatThrownBy(() -> applier().validateBeforeConfirm(payment, List.of(allocation)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PACKAGE_UPGRADE_STATE_CONFLICT);
    }

    /** 승인 전 검사에서 정원 구성이 요청 때와 달라졌으면 Toss 호출 전에 막는다. */
    @Test
    void rejectsChangedCapacityCompositionBeforeConfirm() {
        Registration registration = upgrading();
        additionalPayment(registration);
        when(allocation.getAllocatedAmount()).thenReturn(DIFF);
        PackageUpgrade upgrade = upgrade(registration);
        when(upgradeRepository.findByRegistrationForUpdate("registration", PackageUpgradeStatus.PENDING))
                .thenReturn(Optional.of(upgrade));
        Reservation reservation = mock(Reservation.class);
        when(capacitySupport.lockConsumedReservation(registration)).thenReturn(reservation);
        when(capacitySupport.packageDiff(registration, pkg, reservation)).thenReturn(new CapacityRequirementDiff(
                "reservation", ReservationStatus.CONSUMED, 1L,
                List.of(new CapacityRequirementDiff.Item("cap-new", 0, 1, 1, 0, 0))));

        assertThatThrownBy(() -> applier().validateBeforeConfirm(payment, List.of(allocation)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PACKAGE_UPGRADE_STATE_CONFLICT);
    }

    /** 잡아 둔 held를 확정으로 옮겨 예약을 패키지 구성으로 바꾸고, 종목·항목·상향 상태를 바꾼다. */
    @Test
    void appliesUpgradeInOrder() {
        Registration registration = upgrading();
        additionalPayment(registration);
        when(allocation.getAllocatedAmount()).thenReturn(DIFF);
        PackageUpgrade upgrade = upgrade(registration);
        when(upgradeRepository.findByRegistrationForUpdate("registration", PackageUpgradeStatus.PENDING))
                .thenReturn(Optional.of(upgrade));
        Reservation reservation = mock(Reservation.class);
        CapacityRequirementDiff diff = new CapacityRequirementDiff("reservation", ReservationStatus.CONSUMED, 1L, List.of());
        when(capacitySupport.lockConsumedReservation(registration)).thenReturn(reservation);
        when(capacitySupport.packageDiff(registration, pkg, reservation)).thenReturn(diff);
        when(entityManager.find(EventCategory.class, "xmen")).thenReturn(xmen);

        applier().applyIfPending(payment, List.of(allocation), NOW);

        InOrder order = inOrder(capacitySupport, capacityModificationService, historyRecorder, entryRepository);
        // 잡아 둔 held를 돌려주지 않고 그대로 확정으로 옮긴다.
        order.verify(capacityModificationService).confirmPreheldReservationMove(
                "event", diff, java.util.Map.of("cap-package", 1), NOW);
        verify(capacitySupport, never()).releaseHeld(any(), any());
        order.verify(historyRecorder).completeReservationModificationSnapshot(reservation);
        ArgumentCaptor<RegistrationEntry> saved = ArgumentCaptor.forClass(RegistrationEntry.class);
        order.verify(entryRepository).save(saved.capture());
        assertThat(saved.getValue().getEventCategory()).isSameAs(xmen);
        assertThat(saved.getValue().getName()).isEqualTo("받는사람");
        assertThat(registration.getEventCategory()).isSameAs(pkg);
        assertThat(upgrade.getStatus()).isEqualTo(PackageUpgradeStatus.COMPLETED);
        assertThat(upgrade.getCompletedAt()).isEqualTo(NOW);
    }

    private Registration upgrading() {
        return Registration.builder().id("registration").event(event).eventCategory(avengers)
                .status(RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED)
                .contractAmount(new BigDecimal("75000")).paidAmount(new BigDecimal("40000")).build();
    }

    private void additionalPayment(Registration registration) {
        when(payment.isRegistrationPayment()).thenReturn(true);
        when(payment.getPurpose()).thenReturn(PaymentPurpose.ADDITIONAL_PAYMENT);
        when(allocation.getRegistration()).thenReturn(registration);
    }

    private PackageUpgrade upgrade(Registration registration) {
        return PackageUpgrade.create(registration, pkg, DIFF,
                List.of(new PackageUpgradeEntry("xmen", "받는사람", "010-1111-2222", "1990-01-01", GenderClass.F)),
                List.of(new PackageUpgradeHeldCapacity("cap-package", 1)));
    }

    private static EventCategory single(String id) {
        return EventCategory.builder().id(id).productType(ProductType.SINGLE).parentCategoryId("package").build();
    }
}
