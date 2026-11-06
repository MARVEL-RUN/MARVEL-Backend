package kr.co.teambrain.marvelrun.user.event.command.application.service;

import jakarta.persistence.EntityManager;
import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ProductType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.upgrade.PackageUpgradeStatus;
import kr.co.teambrain.marvelrun.common.json_object.PackageUpgradeEntry;
import kr.co.teambrain.marvelrun.common.json_object.PackageUpgradeHeldCapacity;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityRequirementDiff;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.RegistrationCapacityService;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationEntryDraft;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.PackageUpgrade;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationItem;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult.Order;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.PackageUpgradeRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.PackageUpgradeOrderResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationEntryValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.loader.RegistrationPolicyLoader;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategoryCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.PackageUpgradeCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationItemCommandRepository;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyService;
import kr.co.teambrain.marvelrun.user.payment.command.application.AdditionalPaymentPreparationService;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 상향 요청·결제창 다시 열기·취소가 정원·계약금액·이력·주문을 맞는 순서로 다루는지 본다. */
@ExtendWith(MockitoExtension.class)
class PackageUpgradeCommandServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 10, 0);
    private static final BigDecimal SINGLE_PRICE = new BigDecimal("40000");
    private static final BigDecimal PACKAGE_PRICE = new BigDecimal("75000");
    private static final BigDecimal DIFF = new BigDecimal("35000");

    @Mock private ServerTimeProvider time;
    @Mock private EntityManager entityManager;
    @Mock private RegistrationCapacityService registrationCapacityService;
    @Mock private RegistrationModificationPaymentGuard paymentGuard;
    @Mock private RegistrationActionPolicyService actionPolicies;
    @Mock private EventCategoryCommandRepository categoryRepository;
    @Mock private RegistrationPolicyLoader policyLoader;
    @Mock private RegistrationEntryValidator entryValidator;
    @Mock private PackageUpgradeCommandRepository upgradeRepository;
    @Mock private PackageUpgradeCapacitySupport capacitySupport;
    @Mock private AdditionalPaymentPreparationService additionalPayments;
    @Mock private PackageUpgradePricing pricing;
    @Mock private RegistrationItemCommandRepository itemRepository;
    @Mock private Reservation reservation;

    private final Event event = Event.builder().id("event").build();
    private final EventCategory pkg = EventCategory.builder().id("package").event(event).name("패키지")
            .productType(ProductType.PACKAGE).amount(PACKAGE_PRICE).isActive(true).build();
    private final EventCategory avengers = EventCategory.builder().id("avengers").event(event).name("어벤저스")
            .productType(ProductType.SINGLE).parentCategoryId("package").amount(SINGLE_PRICE).isActive(true).build();
    private final EventCategory xmen = EventCategory.builder().id("xmen").event(event).name("엑스맨")
            .productType(ProductType.SINGLE).parentCategoryId("package").build();

    private PackageUpgradeCommandService service;

    @BeforeEach
    void setUp() {
        service = new PackageUpgradeCommandService(time, entityManager, registrationCapacityService, paymentGuard,
                actionPolicies, categoryRepository, policyLoader, entryValidator, upgradeRepository, capacitySupport,
                additionalPayments, pricing, itemRepository);
        when(time.currentDateTime()).thenReturn(NOW);
    }

    /** 납부액이 비어 있는 이전 데이터는 500이 아니라 상향 불가로 거절하고 정원을 잡지 않는다. */
    @Test
    void rejectsNullPaidAmountBeforeHolding() {
        locked(Registration.builder().id("registration").event(event).eventCategory(avengers)
                .status(RegistrationStatus.CONFIRMED).contractAmount(SINGLE_PRICE).paidAmount(null).build());

        assertThatThrownBy(() -> service.request("registration", request()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PACKAGE_UPGRADE_NOT_ALLOWED);
        verifyNoInteractions(capacitySupport, additionalPayments, pricing);
    }

    /** 결제를 마치지 않은 신청은 정원·주문을 만들기 전에 거절한다. */
    @Test
    void rejectsUnconfirmedRegistrationBeforeHolding() {
        locked(Registration.builder().id("registration").event(event).eventCategory(avengers)
                .status(RegistrationStatus.PAYMENT_PENDING).contractAmount(SINGLE_PRICE)
                .paidAmount(BigDecimal.ZERO).build());

        assertThatThrownBy(() -> service.request("registration", request()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PACKAGE_UPGRADE_NOT_ALLOWED);
        verifyNoInteractions(capacitySupport, additionalPayments, pricing);
    }

    /** 진행 중인 상향이 있으면 다시 요청하지 않는다. */
    @Test
    void rejectsSecondRequestWhilePending() {
        Registration registration = confirmed();
        locked(registration);
        when(upgradeRepository.findByRegistrationForUpdate("registration", PackageUpgradeStatus.PENDING))
                .thenReturn(Optional.of(mock(PackageUpgrade.class)));

        assertThatThrownBy(() -> service.request("registration", request()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PACKAGE_UPGRADE_IN_PROGRESS);
        verifyNoInteractions(capacitySupport, additionalPayments);
    }

    /**
     * 차액은 패키지 가격 계산 결과에서 지금 계약금액을 뺀 값이다.
     * held를 잡은 뒤 이력과 함께 계약금액을 올리고, 패키지 이름의 주문을 같은 기준 시각으로 준비한다.
     */
    @Test
    void requestsUpgradeWithPricedDifference() {
        Registration registration = confirmed();
        locked(registration);
        when(upgradeRepository.findByRegistrationForUpdate("registration", PackageUpgradeStatus.PENDING))
                .thenReturn(Optional.empty());
        when(categoryRepository.findById("package")).thenReturn(Optional.of(pkg));
        when(categoryRepository.findByEvent_IdAndParentCategoryIdOrderByOrderAscIdAsc("event", "package"))
                .thenReturn(List.of(avengers, xmen));
        when(entryValidator.validateUpgrade(any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(List.of(new RegistrationEntryDraft(xmen, "받는사람", "010-1111-2222", "1990-01-01", GenderClass.F)));
        when(pricing.calculate(registration, pkg)).thenReturn(price(PACKAGE_PRICE));
        CapacityRequirementDiff diff = new CapacityRequirementDiff("reservation", ReservationStatus.CONSUMED, 1L, List.of());
        RegistrationItem item = RegistrationItem.create(registration, 1, avengers, List.of(), SINGLE_PRICE);
        when(itemRepository.findByRegistration_IdOrderBySortOrderAsc("registration")).thenReturn(List.of(item));
        when(capacitySupport.lockConsumedReservation(registration)).thenReturn(reservation);
        // 상향 뒤 사람 수(하위 종목 수)만큼 전체 정원을 쓰도록 단품 줄과 하위 종목 수를 넘긴다.
        when(capacitySupport.packageDiff(item, pkg, 2, reservation)).thenReturn(diff);
        when(capacitySupport.holdIncreases("event", diff, NOW))
                .thenReturn(List.of(new PackageUpgradeHeldCapacity("cap-package", 1)));
        runAmountChange();
        when(upgradeRepository.save(any(PackageUpgrade.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(additionalPayments.prepareLockedPackageUpgrade(List.of(), registration, pkg, NOW))
                .thenReturn(new Order("payment-1", "order-1", "대회 - 패키지", DIFF));

        PackageUpgradeOrderResponse response = service.request("registration", request());

        InOrder order = inOrder(capacitySupport, upgradeRepository, additionalPayments);
        order.verify(capacitySupport).holdIncreases("event", diff, NOW);
        order.verify(capacitySupport).recordAmountChange(eq(reservation), eq(NOW), anyString(), any());
        order.verify(upgradeRepository).save(any(PackageUpgrade.class));
        order.verify(additionalPayments).prepareLockedPackageUpgrade(List.of(), registration, pkg, NOW);
        assertThat(registration.getContractAmount()).isEqualByComparingTo(PACKAGE_PRICE);
        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED);
        ArgumentCaptor<PackageUpgrade> saved = ArgumentCaptor.forClass(PackageUpgrade.class);
        verify(upgradeRepository).save(saved.capture());
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo(DIFF);
        assertThat(saved.getValue().getPaymentId()).isEqualTo("payment-1");
        assertThat(response.paymentId()).isEqualTo("payment-1");
    }

    /** 구매 줄이 여럿인 신청은 정원·주문을 만들기 전에 새 오류로 거절한다. 줄 단위 상향은 아직 없다. */
    @Test
    void rejectsMultiItemRegistrationBeforeHolding() {
        Registration registration = confirmed();
        locked(registration);
        when(upgradeRepository.findByRegistrationForUpdate("registration", PackageUpgradeStatus.PENDING))
                .thenReturn(Optional.empty());
        when(itemRepository.findByRegistration_IdOrderBySortOrderAsc("registration")).thenReturn(List.of(
                RegistrationItem.create(registration, 1, avengers, List.of(), SINGLE_PRICE),
                RegistrationItem.create(registration, 2, avengers, List.of(), SINGLE_PRICE)));

        assertThatThrownBy(() -> service.request("registration", request()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PACKAGE_UPGRADE_MULTI_ITEM_NOT_ALLOWED);
        verifyNoInteractions(capacitySupport, additionalPayments, pricing);
    }

    /** 결제창을 다시 열면 상향 대기가 이번 주문을 가리킨다. */
    @Test
    void preparePaymentLinksLatestOrder() {
        Registration registration = upgrading();
        locked(registration);
        PackageUpgrade upgrade = pendingUpgrade(registration);
        upgrade.linkPayment("payment-old");
        when(upgradeRepository.findByRegistrationForUpdate("registration", PackageUpgradeStatus.PENDING))
                .thenReturn(Optional.of(upgrade));
        when(additionalPayments.prepareLockedPackageUpgrade(List.of(), registration, pkg, NOW))
                .thenReturn(new Order("payment-new", "order-new", "대회 - 패키지", DIFF));

        service.preparePayment("registration");

        assertThat(upgrade.getPaymentId()).isEqualTo("payment-new");
    }

    /** 취소는 예약을 잠근 뒤 held를 돌려주고, 이력과 함께 계약금액을 단품 가격으로 되돌린다. */
    @Test
    void cancelReleasesHeldAndRestoresContract() {
        Registration registration = upgrading();
        locked(registration);
        PackageUpgrade upgrade = pendingUpgrade(registration);
        when(upgradeRepository.findByRegistrationForUpdate("registration", PackageUpgradeStatus.PENDING))
                .thenReturn(Optional.of(upgrade));
        when(capacitySupport.lockConsumedReservation(registration)).thenReturn(reservation);
        runAmountChange();

        service.cancel("registration");

        InOrder order = inOrder(paymentGuard, capacitySupport);
        order.verify(paymentGuard).prepareLockedPayments(List.of());
        order.verify(capacitySupport).lockConsumedReservation(registration);
        order.verify(capacitySupport).releaseHeld(upgrade, NOW);
        order.verify(capacitySupport).recordAmountChange(eq(reservation), eq(NOW), anyString(), any());
        assertThat(registration.getContractAmount()).isEqualByComparingTo(SINGLE_PRICE);
        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.CONFIRMED);
        assertThat(upgrade.getStatus()).isEqualTo(PackageUpgradeStatus.CANCELED);
    }

    private void locked(Registration registration) {
        when(entityManager.find(Registration.class, "registration")).thenReturn(registration);
        when(paymentGuard.lockPersonal("event", "registration")).thenReturn(List.of());
    }

    /** recordAmountChange는 mock이므로 넘겨받은 변경을 실제로 실행하게 한다. */
    private void runAmountChange() {
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(3).run();
            return null;
        }).when(capacitySupport).recordAmountChange(any(), any(), anyString(), any());
    }

    private Registration confirmed() {
        return Registration.builder().id("registration").event(event).eventCategory(avengers)
                .status(RegistrationStatus.CONFIRMED).contractAmount(SINGLE_PRICE).paidAmount(SINGLE_PRICE).build();
    }

    private Registration upgrading() {
        return Registration.builder().id("registration").event(event).eventCategory(avengers)
                .status(RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED).contractAmount(PACKAGE_PRICE)
                .paidAmount(SINGLE_PRICE).build();
    }

    private PackageUpgrade pendingUpgrade(Registration registration) {
        return PackageUpgrade.create(registration, pkg, DIFF,
                List.of(new PackageUpgradeEntry("xmen", "받는사람", "010-1111-2222", "1990-01-01", GenderClass.F)),
                List.of(new PackageUpgradeHeldCapacity("cap-package", 1)));
    }

    private static PackageUpgradeRequest request() {
        return new PackageUpgradeRequest(List.of());
    }

    private static DiscountModels.PriceResult price(BigDecimal amount) {
        return new DiscountModels.PriceResult(DiscountModels.Failure.NONE, amount, amount, BigDecimal.ZERO, List.of());
    }
}
