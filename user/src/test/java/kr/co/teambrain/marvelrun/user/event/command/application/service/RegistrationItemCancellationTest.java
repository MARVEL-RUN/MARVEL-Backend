package kr.co.teambrain.marvelrun.user.event.command.application.service;

import jakarta.persistence.EntityManager;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ProductType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationActionType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityRequirementDiff;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityRequirementInput;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.CapacityModificationService;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.RegistrationItemCapacityRequirements;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.ReservationCapacityDiffService;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.ReservationHistoryRecorder;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.ReservationRemovalService;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationCommandRepository;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationItem;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationItemCommandRepository;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyModels.Action;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyService;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentCancelCommandRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 로그인한 주문자의 상품 하나 취소(부분 취소)가 정원·계약금액·대표값·정산을 맞는 순서로 다루는지 본다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RegistrationItemCancellationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 10, 0);

    @Mock private OrganizationLifecycleService organizationLifecycle;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private EntityManager entityManager;
    @Mock private EventCommandRepository events;
    @Mock private RegistrationModificationPaymentGuard paymentGuard;
    @Mock private ReservationCommandRepository reservations;
    @Mock private ReservationRemovalService removal;
    @Mock private RegistrationModificationSettlementService settlement;
    @Mock private PaymentCancelCommandRepository cancellations;
    @Mock private ServerTimeProvider time;
    @Mock private RegistrationActionPolicyService actionPolicies;
    @Mock private PackageUpgradeCapacitySupport packageUpgrades;
    @Mock private RegistrationItemCommandRepository items;
    @Mock private RegistrationItemCapacityRequirements itemRequirements;
    @Mock private ReservationCapacityDiffService diffService;
    @Mock private CapacityModificationService capacityModification;
    @Mock private ReservationHistoryRecorder historyRecorder;
    @Mock private Reservation reservation;
    @InjectMocks private RegistrationCancellationTransactionService service;

    private final Event event = Event.builder().id("event").build();
    private final EventCategory avengers = product("avengers", ProductType.SINGLE);
    private final EventCategory xmen = product("xmen", ProductType.SINGLE);
    private final EventCategory pkg = product("package", ProductType.PACKAGE);
    private final List<SouvenirJson> kit = List.of(new SouvenirJson("kit", "FREE"));
    private final CapacityRequirementDiff diff = new CapacityRequirementDiff("reservation", ReservationStatus.CONSUMED, 1L,
            List.of(new CapacityRequirementDiff.Item("cap-avengers", 1, 0, 0, 1, 0)));

    private Registration registration;
    private RegistrationItem first;
    private RegistrationItem second;
    private RegistrationItem third;

    /** 단품 2(40,000·40,000) + 패키지 1(75,000), 결제 완료 155,000원 주문. 대표 줄은 첫 줄(어벤저스). */
    @BeforeEach
    void setUp() {
        registration = Registration.builder().id("r1").ordererKey("orderer").event(event).eventCategory(avengers)
                .souvenirJson(kit).status(RegistrationStatus.CONFIRMED).currentPriceCalculationId("calc-155000")
                .contractAmount(new BigDecimal("155000")).paidAmount(new BigDecimal("155000")).build();
        first = item("i1", 1, avengers, "40000");
        second = item("i2", 2, xmen, "40000");
        third = item("i3", 3, pkg, "75000");
        when(time.currentDateTime()).thenReturn(NOW);
        when(events.findByIdForUpdate("event")).thenReturn(Optional.of(event));
        when(paymentGuard.lockPersonal("event", "r1")).thenReturn(List.of());
        when(entityManager.find(Registration.class, "r1")).thenReturn(registration);
        when(items.findById("i1")).thenReturn(Optional.of(first));
        when(items.findById("i2")).thenReturn(Optional.of(second));
        when(items.findById("i3")).thenReturn(Optional.of(third));
        when(items.findActiveByRegistrationId("r1")).thenReturn(List.of(first, second, third));
        when(packageUpgrades.lockConsumedReservation(registration)).thenReturn(reservation);
        when(reservation.getId()).thenReturn("reservation");
        when(itemRequirements.participantsByItem("r1")).thenReturn(Map.of("i1", 1, "i2", 1, "i3", 3));
        when(itemRequirements.resolveMerged(eq("event"), anyList())).thenReturn(Map.of("cap-total", 4));
        when(diffService.compareAll(List.of(reservation), Map.of("reservation", Map.of("cap-total", 4))))
                .thenReturn(List.of(diff));
        when(settlement.settleRegistrationModification(eq("event"), eq(null), eq(List.of("r1")), eq(NOW), any()))
                .thenReturn(new RegistrationModificationSettlementResult(List.of(), List.of(), List.of()));
    }

    @Test
    void 단품_하나를_취소하면_남는_줄로_정원을_다시_구해_차이만_돌려주고_줄_금액만큼_계약금액을_줄인다() {
        RegistrationCancellationTransactionService.Prepared prepared = service.cancelOwnedItem("event", "r1", "i2", "orderer");

        // 남는 줄(어벤저스 1명, 패키지 3명)만으로 필요 정원을 구한다.
        ArgumentCaptor<List<CapacityRequirementInput>> inputs = ArgumentCaptor.forClass(List.class);
        verify(itemRequirements).resolveMerged(eq("event"), inputs.capture());
        assertThat(inputs.getValue()).containsExactly(
                new CapacityRequirementInput("avengers", false, List.of(), 1),
                new CapacityRequirementInput("package", false, List.of(), 3));
        InOrder order = inOrder(paymentGuard, capacityModification, historyRecorder, settlement);
        order.verify(paymentGuard).rejectPendingPackageUpgrade("r1", ErrorCode.PACKAGE_UPGRADE_IN_PROGRESS);
        order.verify(paymentGuard).prepareLockedPayments(List.of());
        order.verify(capacityModification).moveReservationCapacities("event", List.of(diff), NOW);
        order.verify(historyRecorder).completeReservationModificationSnapshot(reservation);
        order.verify(settlement).settleRegistrationModification(eq("event"), eq(null), eq(List.of("r1")), eq(NOW), any());
        assertThat(second.getCanceledAt()).isEqualTo(NOW);
        assertThat(first.isCanceled()).isFalse();
        assertThat(registration.getContractAmount()).isEqualByComparingTo("115000");
        // 155,000원 계산 기록은 남은 115,000원의 근거가 아니므로 현재 참조를 끊는다.
        assertThat(registration.getCurrentPriceCalculationId()).isNull();
        assertThat(registration.isSoftDeleted()).isFalse();
        // 대표 줄이 그대로이므로 신청 종목·기념품도 그대로다.
        assertThat(registration.getEventCategory()).isSameAs(avengers);
        assertThat(prepared.executeRefunds()).isTrue();
        verify(actionPolicies).validateRegistrationActionPolicy(eq(event), eq(registration), eq(Action.REFUND), eq(NOW), any());
        verify(actionPolicies).loadEnabledRegistrationActionPolicies("event", RegistrationActionType.REFUND);
        verifyNoInteractions(removal);
    }

    @Test
    void 패키지_줄을_취소하면_통째로_빠지고_계약금액은_패키지_금액만큼_준다() {
        service.cancelOwnedItem("event", "r1", "i3", "orderer");

        ArgumentCaptor<List<CapacityRequirementInput>> inputs = ArgumentCaptor.forClass(List.class);
        verify(itemRequirements).resolveMerged(eq("event"), inputs.capture());
        assertThat(inputs.getValue()).extracting(CapacityRequirementInput::eventCategoryId)
                .containsExactly("avengers", "xmen");
        assertThat(third.isCanceled()).isTrue();
        assertThat(registration.getContractAmount()).isEqualByComparingTo("80000");
    }

    @Test
    void 대표_줄을_취소하면_신청의_종목과_기념품을_남은_첫_줄로_옮긴다() {
        service.cancelOwnedItem("event", "r1", "i1", "orderer");

        assertThat(registration.getEventCategory()).isSameAs(xmen);
        assertThat(registration.getSouvenirJson()).isEqualTo(second.getSouvenirJson());
        assertThat(registration.getContractAmount()).isEqualByComparingTo("115000");
    }

    @Test
    void 남은_마지막_줄을_취소하면_주문_전체_취소로_넘긴다() {
        when(items.findActiveByRegistrationId("r1")).thenReturn(List.of(third));
        when(reservations.findAllByRegistrationIds(List.of("r1"))).thenReturn(List.of(reservation));
        when(reservation.getRegistration()).thenReturn(registration);
        when(reservation.getStatus()).thenReturn(ReservationStatus.CONSUMED);

        service.cancelOwnedItem("event", "r1", "i3", "orderer");

        verify(removal).releaseAll("event", List.of("r1"), NOW);
        assertThat(registration.isSoftDeleted()).isTrue();
        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.CANCELLATION_PENDING);
        // 전체 취소는 줄 단위 정원 계산을 하지 않는다.
        verifyNoInteractions(itemRequirements, capacityModification);
        assertThat(third.isCanceled()).isFalse();
    }

    @Test
    void 결제를_마치지_않은_주문은_상품_하나만_취소하지_않는다() {
        registration = Registration.builder().id("r1").ordererKey("orderer").event(event).eventCategory(avengers)
                .status(RegistrationStatus.PAYMENT_PENDING)
                .contractAmount(new BigDecimal("155000")).paidAmount(BigDecimal.ZERO).build();
        when(entityManager.find(Registration.class, "r1")).thenReturn(registration);

        assertCode(() -> service.cancelOwnedItem("event", "r1", "i2", "orderer"),
                ErrorCode.REGISTRATION_ITEM_CANCEL_NOT_ALLOWED);
        verifyNoInteractions(capacityModification, settlement);
        assertThat(second.isCanceled()).isFalse();
    }

    @Test
    void 상향_대기_중이면_정원과_금액을_건드리지_않고_거절한다() {
        doThrow(new CustomException(ErrorCode.PACKAGE_UPGRADE_IN_PROGRESS))
                .when(paymentGuard).rejectPendingPackageUpgrade("r1", ErrorCode.PACKAGE_UPGRADE_IN_PROGRESS);

        assertCode(() -> service.cancelOwnedItem("event", "r1", "i2", "orderer"), ErrorCode.PACKAGE_UPGRADE_IN_PROGRESS);
        verifyNoInteractions(capacityModification, settlement);
        assertThat(registration.getContractAmount()).isEqualByComparingTo("155000");
    }

    @Test
    void 환불_기간이_지났으면_거절한다() {
        doThrow(new CustomException(ErrorCode.REGISTRATION_REFUND_POLICY_BLOCKED))
                .when(actionPolicies).validateRegistrationActionPolicy(eq(event), eq(registration), eq(Action.REFUND), eq(NOW), any());

        assertThatThrownBy(() -> service.cancelOwnedItem("event", "r1", "i2", "orderer"))
                .isInstanceOf(CustomException.class);
        verifyNoInteractions(capacityModification, settlement);
        verify(paymentGuard, never()).prepareLockedPayments(any());
    }

    @Test
    void 다른_주문의_줄이나_이미_취소한_줄은_거절한다() {
        Registration other = Registration.builder().id("r2").event(event).build();
        when(items.findById("foreign")).thenReturn(Optional.of(item(other, "foreign", 1, avengers, "40000")));
        assertCode(() -> service.cancelOwnedItem("event", "r1", "foreign", "orderer"), ErrorCode.REGISTRATION_ITEM_NOT_FOUND);
        assertCode(() -> service.cancelOwnedItem("event", "r1", "missing", "orderer"), ErrorCode.REGISTRATION_ITEM_NOT_FOUND);

        second.cancel(NOW.minusDays(1));
        assertCode(() -> service.cancelOwnedItem("event", "r1", "i2", "orderer"), ErrorCode.REGISTRATION_ITEM_ALREADY_CANCELED);
        verifyNoInteractions(capacityModification, settlement);
    }

    @Test
    void 남의_주문은_줄을_읽기_전에_거절한다() {
        assertCode(() -> service.cancelOwnedItem("event", "r1", "i2", "other"), ErrorCode.REGISTRATION_ACCESS_DENIED);
        verify(items, never()).findById(any());
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private RegistrationItem item(String id, int sortOrder, EventCategory product, String amount) {
        return item(registration, id, sortOrder, product, amount);
    }

    private RegistrationItem item(Registration owner, String id, int sortOrder, EventCategory product, String amount) {
        return RegistrationItem.builder().id(id).event(event).registration(owner).eventCategory(product)
                .sortOrder(sortOrder).souvenirJson(List.of()).amount(new BigDecimal(amount)).build();
    }

    private EventCategory product(String id, ProductType type) {
        return EventCategory.builder().id(id).event(event).productType(type)
                .parentCategoryId(type == ProductType.SINGLE ? "package" : null).build();
    }
}
