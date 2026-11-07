package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import kr.co.teambrain.marvelrun.common.discount.model.RegistrationPriceCalculationSnapshot;
import kr.co.teambrain.marvelrun.user.discount.service.RegistrationPriceCalculationRecorder;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationEntryDraft;
import jakarta.persistence.EntityManager;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ProductType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;
import kr.co.teambrain.marvelrun.common.inheritance_enum.upgrade.PackageUpgradeStatus;
import kr.co.teambrain.marvelrun.common.json_object.PackageUpgradeEntry;
import kr.co.teambrain.marvelrun.common.json_object.PackageUpgradeHeldCapacity;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
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
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationItem;
import kr.co.teambrain.marvelrun.user.event.command.repository.PackageUpgradeCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationEntryCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationItemCommandRepository;
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
    @Mock private PackageUpgradePricing pricing;
    @Mock private RegistrationPriceCalculationRecorder priceCalculations;
    @Mock private RegistrationItemCommandRepository itemRepository;
    @Mock private Payment payment;
    @Mock private PaymentAllocation allocation;

    private final Event event = Event.builder().id("event").build();
    private final EventCategory pkg = EventCategory.builder().id("package").event(event)
            .productType(ProductType.PACKAGE).amount(new BigDecimal("75000")).build();
    private final EventCategory avengers = single("avengers");
    private final EventCategory xmen = single("xmen");

    private PackageUpgradeApplier applier() {
        return new PackageUpgradeApplier(entityManager, upgradeRepository, entryRepository, capacitySupport,
                capacityModificationService, historyRecorder, pricing, priceCalculations, itemRepository);
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
        RegistrationItem item = singleEntry(registration);
        Reservation reservation = mock(Reservation.class);
        when(capacitySupport.lockConsumedReservation(registration)).thenReturn(reservation);
        when(capacitySupport.packageDiff(List.of(item), item, pkg, 2, reservation)).thenReturn(new CapacityRequirementDiff(
                "reservation", ReservationStatus.CONSUMED, 1L,
                List.of(new CapacityRequirementDiff.Item("cap-new", 0, 1, 1, 0, 0))));

        // 참가자가 스스로 풀 수 있도록 응답 문구에 해결 방법을 담는다.
        assertThatThrownBy(() -> applier().validateBeforeConfirm(payment, List.of(allocation)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.PACKAGE_UPGRADE_STATE_CONFLICT);
                    assertThat(((CustomException) e).getResponseMessage()).contains("상향을 취소한 뒤 다시 요청");
                });
        verify(capacitySupport, never()).validatePreheldMove(any(), any(), any());
    }

    /** 참가 항목이 단품 하나가 아니면 승인 뒤 패키지 항목이 어긋나므로 Toss 호출 전에 막는다. */
    @Test
    void rejectsWhenEntriesAreNotSingleBeforeConfirm() {
        Registration registration = upgrading();
        additionalPayment(registration);
        when(allocation.getAllocatedAmount()).thenReturn(DIFF);
        when(upgradeRepository.findByRegistrationForUpdate("registration", PackageUpgradeStatus.PENDING))
                .thenReturn(Optional.of(upgrade(registration)));
        singleItem(registration);
        when(entryRepository.findByRegistration_Id("registration")).thenReturn(List.of());

        assertThatThrownBy(() -> applier().validateBeforeConfirm(payment, List.of(allocation)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PACKAGE_UPGRADE_STATE_CONFLICT);
        verifyNoInteractions(capacitySupport, pricing);
    }

    /** 고른 줄이 취소됐거나 살아 있는 줄에 없으면 Toss 호출 전에 막는다. */
    @Test
    void rejectsWhenSourceItemIsNoLongerActiveBeforeConfirm() {
        Registration registration = upgrading();
        additionalPayment(registration);
        when(allocation.getAllocatedAmount()).thenReturn(DIFF);
        when(upgradeRepository.findByRegistrationForUpdate("registration", PackageUpgradeStatus.PENDING))
                .thenReturn(Optional.of(upgrade(registration)));
        when(itemRepository.findActiveByRegistrationId("registration")).thenReturn(List.of(
                item(registration, "item-2", 2, xmen, "40000")));

        assertThatThrownBy(() -> applier().validateBeforeConfirm(payment, List.of(allocation)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PACKAGE_UPGRADE_STATE_CONFLICT);
        verifyNoInteractions(capacitySupport, pricing);
    }

    /**
     * 줄이 여럿인 주문은 고른 단품 줄만 패키지로 바꾼다. 대표 줄이 아니면 신청 종목은 그대로이고,
     * 신청 전체 금액과 같은 가격 기록을 만들 수 없으므로 가격 기록을 남기지 않는다.
     */
    @Test
    void appliesUpgradeToChosenLineAmongMany() {
        EventCategory packageProduct = EventCategory.builder().id("package").event(event)
                .productType(ProductType.PACKAGE).amount(new BigDecimal("75000")).build();
        Registration registration = Registration.builder().id("registration").event(event).eventCategory(packageProduct)
                .status(RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED).currentPriceCalculationId("calc-115000")
                .contractAmount(new BigDecimal("150000")).paidAmount(new BigDecimal("115000")).build();
        additionalPayment(registration);
        when(allocation.getAllocatedAmount()).thenReturn(DIFF);
        RegistrationItem first = item(registration, "item-0", 1, packageProduct, "75000");
        RegistrationItem chosen = item(registration, "item-1", 2, avengers, "40000");
        List<RegistrationItem> active = List.of(first, chosen);
        when(itemRepository.findActiveByRegistrationId("registration")).thenReturn(active);
        PackageUpgrade upgrade = upgrade(registration);
        when(upgradeRepository.findByRegistrationForUpdate("registration", PackageUpgradeStatus.PENDING))
                .thenReturn(Optional.of(upgrade));
        Reservation reservation = mock(Reservation.class);
        CapacityRequirementDiff diff = new CapacityRequirementDiff("reservation", ReservationStatus.CONSUMED, 1L, List.of());
        when(capacitySupport.lockConsumedReservation(registration)).thenReturn(reservation);
        when(capacitySupport.packageDiff(active, chosen, pkg, 2, reservation)).thenReturn(diff);
        List<SouvenirJson> kits = List.of(new SouvenirJson("kit-avengers", "FREE"), new SouvenirJson("kit-xmen", "FREE"));
        when(capacitySupport.packageSouvenirs(chosen, pkg)).thenReturn(kits);
        when(entityManager.find(EventCategory.class, "xmen")).thenReturn(xmen);
        when(pricing.calculate(registration, pkg)).thenReturn(price("75000"));
        List<SouvenirJson> representativeSouvenirs = registration.getSouvenirJson();

        applier().applyIfPending(payment, List.of(allocation), NOW);

        assertThat(chosen.getEventCategory()).isSameAs(pkg);
        assertThat(chosen.getAmount()).isEqualByComparingTo("75000");
        assertThat(chosen.getSouvenirJson()).isEqualTo(kits);
        assertThat(first.getEventCategory()).isSameAs(packageProduct);
        assertThat(registration.getEventCategory()).isSameAs(packageProduct);
        // 대표 줄이 아니므로 신청 행의 기념품도 그대로다.
        assertThat(registration.getSouvenirJson()).isEqualTo(representativeSouvenirs);
        verifyNoInteractions(priceCalculations);
        // 상향 전 115,000원 기록은 150,000원의 근거가 아니므로 현재 참조를 끊는다.
        assertThat(registration.getCurrentPriceCalculationId()).isNull();
        ArgumentCaptor<RegistrationEntry> saved = ArgumentCaptor.forClass(RegistrationEntry.class);
        verify(entryRepository).save(saved.capture());
        assertThat(saved.getValue().getRegistrationItem()).isSameAs(chosen);
        assertThat(upgrade.getStatus()).isEqualTo(PackageUpgradeStatus.COMPLETED);
    }

    /** 정원 숫자 검사에서 막히면 가격 계산까지 가지 않는다. */
    @Test
    void rejectsShortCapacityCountersBeforeConfirm() {
        Registration registration = upgrading();
        additionalPayment(registration);
        when(allocation.getAllocatedAmount()).thenReturn(DIFF);
        PackageUpgrade upgrade = upgrade(registration);
        when(upgradeRepository.findByRegistrationForUpdate("registration", PackageUpgradeStatus.PENDING))
                .thenReturn(Optional.of(upgrade));
        RegistrationItem item = singleEntry(registration);
        Reservation reservation = mock(Reservation.class);
        CapacityRequirementDiff diff = matchingDiff();
        when(capacitySupport.lockConsumedReservation(registration)).thenReturn(reservation);
        when(capacitySupport.packageDiff(List.of(item), item, pkg, 2, reservation)).thenReturn(diff);
        doThrow(new CustomException(ErrorCode.CAPACITY_COUNTER_MISMATCH))
                .when(capacitySupport).validatePreheldMove("event", diff, upgrade);

        assertThatThrownBy(() -> applier().validateBeforeConfirm(payment, List.of(allocation)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CAPACITY_COUNTER_MISMATCH);
        verifyNoInteractions(pricing);
    }

    /** 패키지 가격 계산이 상향 계약금액과 다르면 승인 뒤 가격 기록이 실패하므로 Toss 호출 전에 막는다. */
    @Test
    void rejectsPriceMismatchBeforeConfirm() {
        Registration registration = upgrading();
        additionalPayment(registration);
        when(allocation.getAllocatedAmount()).thenReturn(DIFF);
        PackageUpgrade upgrade = upgrade(registration);
        when(upgradeRepository.findByRegistrationForUpdate("registration", PackageUpgradeStatus.PENDING))
                .thenReturn(Optional.of(upgrade));
        RegistrationItem item = singleEntry(registration);
        Reservation reservation = mock(Reservation.class);
        when(capacitySupport.lockConsumedReservation(registration)).thenReturn(reservation);
        when(capacitySupport.packageDiff(List.of(item), item, pkg, 2, reservation)).thenReturn(matchingDiff());
        when(pricing.calculate(registration, pkg)).thenReturn(price("80000"));

        assertThatThrownBy(() -> applier().validateBeforeConfirm(payment, List.of(allocation)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PACKAGE_UPGRADE_STATE_CONFLICT);
    }

    /** 단품 하나, 정원 숫자, 가격이 모두 맞으면 통과한다. */
    @Test
    void passesBeforeConfirmWhenApplicable() {
        Registration registration = upgrading();
        additionalPayment(registration);
        when(allocation.getAllocatedAmount()).thenReturn(DIFF);
        PackageUpgrade upgrade = upgrade(registration);
        when(upgradeRepository.findByRegistrationForUpdate("registration", PackageUpgradeStatus.PENDING))
                .thenReturn(Optional.of(upgrade));
        RegistrationItem item = singleEntry(registration);
        Reservation reservation = mock(Reservation.class);
        CapacityRequirementDiff diff = matchingDiff();
        when(capacitySupport.lockConsumedReservation(registration)).thenReturn(reservation);
        when(capacitySupport.packageDiff(List.of(item), item, pkg, 2, reservation)).thenReturn(diff);
        when(pricing.calculate(registration, pkg)).thenReturn(price("75000"));

        applier().validateBeforeConfirm(payment, List.of(allocation));

        verify(capacitySupport).validatePreheldMove("event", diff, upgrade);
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
        RegistrationItem item = singleItem(registration);
        when(capacitySupport.lockConsumedReservation(registration)).thenReturn(reservation);
        when(capacitySupport.packageDiff(List.of(item), item, pkg, 2, reservation)).thenReturn(diff);
        List<SouvenirJson> kits = List.of(new SouvenirJson("kit-avengers", "FREE"), new SouvenirJson("kit-xmen", "FREE"));
        when(capacitySupport.packageSouvenirs(item, pkg)).thenReturn(kits);
        when(entityManager.find(EventCategory.class, "xmen")).thenReturn(xmen);
        DiscountModels.PriceResult price = price("75000");
        when(pricing.calculate(registration, pkg)).thenReturn(price);

        applier().applyIfPending(payment, List.of(allocation), NOW);

        InOrder order = inOrder(capacitySupport, capacityModificationService, historyRecorder, priceCalculations,
                entryRepository);
        // 잡아 둔 held를 돌려주지 않고 그대로 확정으로 옮긴다.
        order.verify(capacityModificationService).confirmPreheldReservationMove(
                "event", diff, java.util.Map.of("cap-package", 1), NOW);
        verify(capacitySupport, never()).releaseHeld(any(), any());
        order.verify(historyRecorder).completeReservationModificationSnapshot(reservation);
        // 패키지 기준 가격 계산을 상향 사유로 남긴다.
        order.verify(priceCalculations).recordRegistrationPriceCalculation(registration, price,
                RegistrationPriceCalculationSnapshot.Reason.PACKAGE_UPGRADED);
        ArgumentCaptor<RegistrationEntry> saved = ArgumentCaptor.forClass(RegistrationEntry.class);
        order.verify(entryRepository).save(saved.capture());
        assertThat(saved.getValue().getEventCategory()).isSameAs(xmen);
        assertThat(saved.getValue().getName()).isEqualTo("받는사람");
        // 더한 항목은 상향한 구매 줄에 묶이고, 줄의 상품·금액·기념품도 패키지로 바뀐다.
        assertThat(saved.getValue().getRegistrationItem()).isSameAs(item);
        assertThat(item.getEventCategory()).isSameAs(pkg);
        assertThat(item.getAmount()).isEqualByComparingTo("75000");
        assertThat(item.getSouvenirJson()).isEqualTo(kits);
        assertThat(registration.getEventCategory()).isSameAs(pkg);
        assertThat(registration.getSouvenirJson()).isEqualTo(kits);
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

    /** 요청 때 고른 단품 줄은 item-1이다. */
    private PackageUpgrade upgrade(Registration registration) {
        return PackageUpgrade.create(registration, RegistrationItem.builder().id("item-1").build(), pkg, DIFF,
                List.of(new PackageUpgradeEntry("xmen", "받는사람", "010-1111-2222", "1990-01-01", GenderClass.F)),
                List.of(new PackageUpgradeHeldCapacity("cap-package", 1)));
    }

    /** 단품 줄 하나와 그 줄의 참가 항목 하나를 준비한다. */
    private RegistrationItem singleEntry(Registration registration) {
        RegistrationItem item = singleItem(registration);
        when(entryRepository.findByRegistration_Id("registration")).thenReturn(List.of(RegistrationEntry.create(
                item, new RegistrationEntryDraft(avengers, "신청자", "010-0000-0000", "1990-01-01", GenderClass.M))));
        return item;
    }

    /** 신청의 구매 줄이 지금 단품 하나뿐이다. */
    private RegistrationItem singleItem(Registration registration) {
        RegistrationItem item = item(registration, "item-1", 1, avengers, "40000");
        when(itemRepository.findActiveByRegistrationId("registration")).thenReturn(List.of(item));
        return item;
    }

    private RegistrationItem item(Registration registration, String id, int sortOrder, EventCategory product, String amount) {
        return RegistrationItem.builder().id(id).event(event).registration(registration).eventCategory(product)
                .sortOrder(sortOrder).souvenirJson(List.of()).amount(new BigDecimal(amount)).build();
    }

    /** 요청 때 잡은 held(cap-package 1)와 같은 증가분. */
    private static CapacityRequirementDiff matchingDiff() {
        return new CapacityRequirementDiff("reservation", ReservationStatus.CONSUMED, 1L,
                List.of(new CapacityRequirementDiff.Item("cap-package", 0, 1, 1, 0, 0)));
    }

    private static DiscountModels.PriceResult price(String amount) {
        return new DiscountModels.PriceResult(DiscountModels.Failure.NONE, new BigDecimal(amount), new BigDecimal(amount),
                BigDecimal.ZERO, List.of());
    }

    private static EventCategory single(String id) {
        return EventCategory.builder().id(id).productType(ProductType.SINGLE).parentCategoryId("package").build();
    }
}
