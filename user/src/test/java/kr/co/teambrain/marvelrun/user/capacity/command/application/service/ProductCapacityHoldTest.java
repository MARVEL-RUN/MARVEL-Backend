package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ProductType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.CapacityType;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Capacity;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.ReservationItem;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityTarget;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.CapacityCommandRepository;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationCommandRepository;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationItemCommandRepository;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationEntryDraft;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationEntry;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationItem;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationApplyValidator;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationEntryCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationItemCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.valid.EventPaymentPolicyValidator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 상품 신청의 정원 확보가 신청 행의 대표 상품이 아니라 구매 줄마다 계산되고,
 * 같은 정원 행은 신청 안에서 수량을 합쳐 예약 상세 한 줄로 저장되는지 본다.
 * 전체 정원은 참가 항목 수(사람 수)만큼 쓴다.
 */
class ProductCapacityHoldTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 5, 12, 0);
    private static final List<SouvenirJson> KIT = List.of(new SouvenirJson("kit", "FREE"));

    private final CapacityCommandRepository capacities = mock(CapacityCommandRepository.class);
    private final ReservationCommandRepository reservations = mock(ReservationCommandRepository.class);
    private final ReservationItemCommandRepository reservationItems = mock(ReservationItemCommandRepository.class);
    private final RegistrationItemCommandRepository registrationItems = mock(RegistrationItemCommandRepository.class);
    private final RegistrationEntryCommandRepository registrationEntries = mock(RegistrationEntryCommandRepository.class);

    private final CapacityHoldService holdService = new CapacityHoldService(
            mock(ReservationHistoryRecorder.class), capacities, reservations, reservationItems,
            new CapacityRequirementResolver(capacities));
    private final RegistrationCapacityService service = new RegistrationCapacityService(
            mock(RegistrationApplyValidator.class), mock(EventPaymentPolicyValidator.class),
            mock(EventCommandRepository.class), capacities, holdService, reservations,
            mock(PaymentCommandRepository.class), registrationItems, registrationEntries);

    private final Event event = Event.builder().id("event").startDate(LocalDateTime.of(2026, 12, 1, 9, 0)).build();
    private final EventCategory pack = EventCategory.builder().id("package").productType(ProductType.PACKAGE).build();
    private final EventCategory avengers = EventCategory.builder().id("avengers").productType(ProductType.SINGLE)
            .parentCategoryId("package").build();
    private final EventCategory xmen = EventCategory.builder().id("xmen").productType(ProductType.SINGLE)
            .parentCategoryId("package").build();
    private final EventCategory fantastic4 = EventCategory.builder().id("fantastic4").productType(ProductType.SINGLE)
            .parentCategoryId("package").build();

    /**
     * 어벤저스 단품 2개 + 3종 패키지 1개: 전체 정원은 사람 수 5(1 + 1 + 3),
     * 어벤저스 종목 2, 패키지 종목 1, 공통 묶음 3, 키트 3을 한 번에 잡고 예약 상세는 정원 행마다 한 줄이다.
     */
    @Test
    void sumsPerCapacityAcrossItemsIntoOneReservation() {
        Registration registration = registration(avengers);
        RegistrationItem first = item(registration, "item-1", 1, avengers, "40000");
        RegistrationItem second = item(registration, "item-2", 2, avengers, "40000");
        RegistrationItem third = item(registration, "item-3", 3, pack, "75000");
        when(registrationItems.findActiveByRegistrationIds(List.of("registration"))).thenReturn(List.of(first, second, third));
        when(registrationEntries.findByRegistration_IdIn(List.of("registration"))).thenReturn(List.of(
                entry(first, avengers), entry(second, avengers),
                entry(third, avengers), entry(third, xmen), entry(third, fantastic4)));
        targets();
        Map<String, Integer> acquired = new HashMap<>();
        when(capacities.acquireHeld(eq("event"), anyString(), anyInt(), eq(NOW))).thenAnswer(invocation -> {
            acquired.merge(invocation.getArgument(1), invocation.getArgument(2), Integer::sum);
            return 1;
        });
        when(capacities.getReferenceById(anyString())).thenAnswer(invocation -> capacity(invocation.getArgument(0)));
        when(reservations.save(any(Reservation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.holdAndCloseIfFull(event, List.of(registration), NOW);

        assertThat(acquired).containsExactlyInAnyOrderEntriesOf(Map.of(
                "total", 5, "avengers-cap", 2, "package-cap", 1, "group-cap", 3, "kit-cap", 3));
        // 같은 정원 행을 한 번씩만 갱신한다.
        verify(capacities, times(5)).acquireHeld(eq("event"), anyString(), anyInt(), eq(NOW));
        verify(reservations, times(1)).save(any(Reservation.class));
        ArgumentCaptor<ReservationItem> saved = ArgumentCaptor.forClass(ReservationItem.class);
        verify(reservationItems, times(5)).save(saved.capture());
        Map<String, Integer> stored = new HashMap<>();
        for (ReservationItem item : saved.getAllValues()) {
            assertThat(stored.put(item.getCapacity().getId(), item.getQuantity())).isNull();
        }
        assertThat(stored).isEqualTo(acquired);
    }

    /** 일반 종목 신청은 구매 줄을 읽지 않고 지금처럼 신청 행 하나로 전체 정원 1을 쓴다. */
    @Test
    void nonProductRegistrationKeepsSingleRequirement() {
        EventCategory legacy = EventCategory.builder().id("legacy").build();
        Registration registration = registration(legacy);
        when(capacities.findRegistrationTargets("event", "legacy", CapacityType.EVENT_TOTAL)).thenReturn(List.of(
                new CapacityTarget("total", CapacityType.EVENT_TOTAL, null, ""),
                new CapacityTarget("legacy-cap", CapacityType.CATEGORY, null, "")));
        when(capacities.findSouvenirTargets("event", Set.of("kit")))
                .thenReturn(List.of(new CapacityTarget("kit-cap", CapacityType.SOUVENIR, "kit", "")));
        List<Integer> totals = new ArrayList<>();
        when(capacities.acquireHeld(eq("event"), anyString(), anyInt(), eq(NOW))).thenAnswer(invocation -> {
            if ("total".equals(invocation.getArgument(1))) {
                totals.add(invocation.getArgument(2));
            }
            return 1;
        });
        when(capacities.getReferenceById(anyString())).thenAnswer(invocation -> capacity(invocation.getArgument(0)));
        when(reservations.save(any(Reservation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.holdAndCloseIfFull(event, List.of(registration), NOW);

        assertThat(totals).containsExactly(1);
        verifyNoInteractions(registrationItems, registrationEntries);
    }

    /** 상품 신청인데 구매 줄이 없으면 대표 상품으로 대신 계산하지 않고 설정 오류로 멈춘다. */
    @Test
    void productRegistrationWithoutItemsIsRejected() {
        Registration registration = registration(avengers);
        when(registrationItems.findActiveByRegistrationIds(List.of("registration"))).thenReturn(List.of());

        assertThatThrownBy(() -> service.holdAndCloseIfFull(event, List.of(registration), NOW))
                .isInstanceOfSatisfying(CustomException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CAPACITY_CONFIGURATION_ERROR));
        verify(capacities, never()).acquireHeld(any(), any(), anyInt(), any());
    }

    private void targets() {
        when(capacities.findRegistrationTargets("event", "avengers", CapacityType.EVENT_TOTAL)).thenReturn(List.of(
                new CapacityTarget("total", CapacityType.EVENT_TOTAL, null, ""),
                new CapacityTarget("avengers-cap", CapacityType.CATEGORY, null, ""),
                new CapacityTarget("group-cap", CapacityType.CATEGORY_GROUP, null, "")));
        when(capacities.findRegistrationTargets("event", "package", CapacityType.EVENT_TOTAL)).thenReturn(List.of(
                new CapacityTarget("total", CapacityType.EVENT_TOTAL, null, ""),
                new CapacityTarget("package-cap", CapacityType.CATEGORY, null, ""),
                new CapacityTarget("group-cap", CapacityType.CATEGORY_GROUP, null, "")));
        when(capacities.findSouvenirTargets("event", Set.of("kit")))
                .thenReturn(List.of(new CapacityTarget("kit-cap", CapacityType.SOUVENIR, "kit", "")));
    }

    private Registration registration(EventCategory representative) {
        return Registration.builder().id("registration").event(event).eventCategory(representative)
                .souvenirJson(KIT).birth("1990-01-01").build();
    }

    private static RegistrationEntry entry(RegistrationItem item, EventCategory category) {
        return RegistrationEntry.create(item, new RegistrationEntryDraft(category, "받는사람", "010-0000-0000",
                "1990-01-01", GenderClass.M));
    }

    private static Capacity capacity(String id) {
        Capacity capacity = mock(Capacity.class);
        when(capacity.getId()).thenReturn(id);
        return capacity;
    }

    /** 저장된 구매 줄. 사람 수를 줄 id로 묶으므로 id를 넣는다. */
    private static RegistrationItem item(Registration registration, String id, int order, EventCategory product, String amount) {
        return RegistrationItem.builder().id(id).event(registration.getEvent()).registration(registration)
                .eventCategory(product).sortOrder(order).souvenirJson(KIT).amount(new BigDecimal(amount)).build();
    }
}
