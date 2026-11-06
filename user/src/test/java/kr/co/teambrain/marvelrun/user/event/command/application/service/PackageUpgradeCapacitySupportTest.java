package kr.co.teambrain.marvelrun.user.event.command.application.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ProductType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.json_object.PackageUpgradeHeldCapacity;
import kr.co.teambrain.marvelrun.common.json_object.ReservationHistoryEntry;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Capacity;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityRequirementDiff;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityRequirementInput;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.ReservationAllocation;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.CapacityRequirementResolver;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.RegistrationItemCapacityRequirements;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.ReservationCapacityDiffService;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.ReservationHistoryRecorder;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.CapacityCommandRepository;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationCommandRepository;
import kr.co.teambrain.marvelrun.user.capacity.command.repository.ReservationItemCommandRepository;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategorySouvenir;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.PackageUpgrade;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationEntry;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationItem;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Souvenir;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategorySouvenirCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.PackageUpgradeCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationEntryCommandRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 상향의 승인 전 정원 숫자 검사와 계약금액 변경 이력을 본다. */
@ExtendWith(MockitoExtension.class)
class PackageUpgradeCapacitySupportTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 12, 10, 0);

    @Mock private EntityManager entityManager;
    @Mock private ReservationCommandRepository reservationRepository;
    @Mock private CapacityRequirementResolver requirementResolver;
    @Mock private RegistrationEntryCommandRepository entryRepository;
    @Mock private ReservationCapacityDiffService diffService;
    @Mock private CapacityCommandRepository capacityRepository;
    @Mock private PackageUpgradeCommandRepository upgradeRepository;
    @Mock private ReservationItemCommandRepository reservationItemRepository;
    @Mock private ReservationHistoryRecorder historyRecorder;
    @Mock private EventCategorySouvenirCommandRepository souvenirMappingRepository;
    @Mock private PackageUpgrade upgrade;

    private final Event event = Event.builder().id("event").build();

    private PackageUpgradeCapacitySupport support() {
        return new PackageUpgradeCapacitySupport(entityManager, reservationRepository,
                new RegistrationItemCapacityRequirements(requirementResolver, entryRepository), diffService,
                capacityRepository, upgradeRepository, reservationItemRepository, historyRecorder,
                souvenirMappingRepository);
    }

    /**
     * 상향 뒤 기념품은 패키지에 연결된 것 전부를 기념품 순서대로 담는다.
     * 단품 줄에서 고른 키트는 그 선택을 그대로 두고, 나머지 부문 키트는 FREE로 더한다.
     */
    @Test
    void packageSouvenirsKeepsSingleSelectionAndAddsOtherKits() {
        EventCategory pkg = EventCategory.builder().id("package").build();
        List<EventCategorySouvenir> mappings = List.of(
                mapping(souvenir("kit-xmen", 2L, "FREE", true)),
                mapping(souvenir("kit-avengers", 1L, "FREE", true)),
                mapping(souvenir("kit-fantastic4", 3L, null, true)));
        when(souvenirMappingRepository.findAllMappingsByCategoryId("package")).thenReturn(mappings);

        List<SouvenirJson> result = support().packageSouvenirs(singleItem(List.of(new SouvenirJson("kit-avengers", "FREE"))), pkg);

        assertThat(result).containsExactly(
                new SouvenirJson("kit-avengers", "FREE"),
                new SouvenirJson("kit-xmen", "FREE"),
                new SouvenirJson("kit-fantastic4", "FREE"));
    }

    /** 상향 요청은 기념품을 받지 않으므로, 새로 더할 기념품이 사이즈를 골라야 하면 설정 오류로 막는다. */
    @Test
    void packageSouvenirsRejectsSizedSouvenirToAdd() {
        EventCategory pkg = EventCategory.builder().id("package").build();
        List<EventCategorySouvenir> mappings = List.of(mapping(souvenir("shirt", 1L, "S|M|L", true)));
        when(souvenirMappingRepository.findAllMappingsByCategoryId("package")).thenReturn(mappings);

        assertThatThrownBy(() -> support().packageSouvenirs(singleItem(List.of()), pkg))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.REGISTRATION_POLICY_CONFIGURATION_ERROR);
    }

    /** 단품 줄이 받은 기념품이 패키지에 연결돼 있지 않으면 상향 뒤 빠지므로 설정 오류로 막는다. */
    @Test
    void packageSouvenirsRejectsSingleSouvenirNotLinkedToPackage() {
        EventCategory pkg = EventCategory.builder().id("package").build();
        List<EventCategorySouvenir> mappings = List.of(mapping(souvenir("kit-xmen", 1L, "FREE", true)));
        when(souvenirMappingRepository.findAllMappingsByCategoryId("package")).thenReturn(mappings);

        assertThatThrownBy(() -> support().packageSouvenirs(
                singleItem(List.of(new SouvenirJson("kit-avengers", "FREE"))), pkg))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.REGISTRATION_POLICY_CONFIGURATION_ERROR);
    }

    /** 새로 더할 기념품이 비활성이면 신청 때와 같은 오류로 막는다. */
    @Test
    void packageSouvenirsRejectsInactiveSouvenirToAdd() {
        EventCategory pkg = EventCategory.builder().id("package").build();
        List<EventCategorySouvenir> mappings = List.of(mapping(souvenir("kit-xmen", 1L, "FREE", false)));
        when(souvenirMappingRepository.findAllMappingsByCategoryId("package")).thenReturn(mappings);

        assertThatThrownBy(() -> support().packageSouvenirs(singleItem(List.of()), pkg))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.SOUVENIR_NOT_ACTIVE);
    }

    /** 정원 차이는 단품 줄 기념품이 아니라 패키지 기념품 전부로 계산한다. */
    @Test
    void packageDiffUsesPackageSouvenirs() {
        EventCategory pkg = EventCategory.builder().id("package").build();
        List<EventCategorySouvenir> mappings = List.of(
                mapping(souvenir("kit-avengers", 1L, "FREE", true)),
                mapping(souvenir("kit-xmen", 2L, "FREE", true)));
        when(souvenirMappingRepository.findAllMappingsByCategoryId("package")).thenReturn(mappings);
        Reservation reservation = mock(Reservation.class);
        when(reservation.getId()).thenReturn("reservation");
        when(requirementResolver.resolveAll(eq("event"), any())).thenReturn(List.of(java.util.Map.of()));
        when(diffService.compareAll(any(), any())).thenReturn(List.of(diff()));

        RegistrationItem item = singleItem(List.of(new SouvenirJson("kit-avengers", "FREE")));

        support().packageDiff(List.of(item), item, pkg, 3, reservation);

        verify(requirementResolver).resolveAll("event", List.of(new CapacityRequirementInput("package", false,
                List.of(new SouvenirJson("kit-avengers", "FREE"), new SouvenirJson("kit-xmen", "FREE")), 3)));
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

    /**
     * 줄이 여럿인 주문은 고른 단품 줄만 패키지 입력(하위 종목 수만큼 사람)으로 바꾸고,
     * 다른 줄은 지금 구성 그대로 계산해 정원 행별로 더한 값을 지금 예약과 비교한다.
     */
    @Test
    void packageDiffChangesOnlyChosenLine() {
        EventCategory pkg = EventCategory.builder().id("package").productType(ProductType.PACKAGE).build();
        EventCategory avengers = EventCategory.builder().id("avengers").productType(ProductType.SINGLE)
                .parentCategoryId("package").build();
        Registration registration = Registration.builder().id("registration").event(event).build();
        RegistrationItem packaged = RegistrationItem.builder().id("item-0").registration(registration)
                .eventCategory(pkg).sortOrder(1).souvenirJson(List.of()).amount(new BigDecimal("75000")).build();
        RegistrationItem single = RegistrationItem.builder().id("item-1").registration(registration)
                .eventCategory(avengers).sortOrder(2).souvenirJson(List.of()).amount(new BigDecimal("40000")).build();
        when(entryRepository.findByRegistration_Id("registration")).thenReturn(List.of(
                entry(packaged), entry(packaged), entry(packaged), entry(single)));
        when(requirementResolver.resolveAll(eq("event"), any())).thenReturn(List.of(
                Map.of("cap-total", 3, "cap-package", 1),
                Map.of("cap-total", 3, "cap-package", 1)));
        Reservation reservation = mock(Reservation.class);
        when(reservation.getId()).thenReturn("reservation");
        CapacityRequirementDiff expected = diff();
        when(diffService.compareAll(List.of(reservation),
                Map.of("reservation", Map.of("cap-total", 6, "cap-package", 2)))).thenReturn(List.of(expected));

        CapacityRequirementDiff result = support().packageDiff(List.of(packaged, single), single, pkg, 3, reservation);

        assertThat(result).isSameAs(expected);
        ArgumentCaptor<List<CapacityRequirementInput>> inputs = ArgumentCaptor.forClass(List.class);
        verify(requirementResolver).resolveAll(eq("event"), inputs.capture());
        assertThat(inputs.getValue()).containsExactly(
                new CapacityRequirementInput("package", false, List.of(), 3),
                new CapacityRequirementInput("package", false, List.of(), 3));
    }

    /** 고른 줄이 살아 있는 줄에 없으면(취소됨) 정원을 계산하지 않는다. */
    @Test
    void packageDiffRejectsLineOutsideActiveLines() {
        EventCategory pkg = EventCategory.builder().id("package").productType(ProductType.PACKAGE).build();
        Registration registration = Registration.builder().id("registration").event(event).build();
        RegistrationItem canceled = RegistrationItem.builder().id("item-1").registration(registration).eventCategory(pkg).build();

        assertThatThrownBy(() -> support().packageDiff(List.of(), canceled, pkg, 3, mock(Reservation.class)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PACKAGE_UPGRADE_STATE_CONFLICT);
        verifyNoInteractions(requirementResolver, diffService);
    }

    private static RegistrationEntry entry(RegistrationItem item) {
        return RegistrationEntry.builder().registrationItem(item).build();
    }

    /** 원래 단품 CATEGORY는 1 줄고 패키지 세트는 1 는다. */
    private static CapacityRequirementDiff diff() {
        return new CapacityRequirementDiff("reservation", ReservationStatus.CONSUMED, 1L, List.of(
                new CapacityRequirementDiff.Item("cap-a-single", 1, 0, 0, 1, 0),
                new CapacityRequirementDiff.Item("cap-b-package", 0, 1, 1, 0, 0)));
    }

    /** 대회에 속한 신청의 단품 구매 줄. */
    private RegistrationItem singleItem(List<SouvenirJson> souvenirs) {
        Registration registration = Registration.builder().id("registration").event(event).build();
        return RegistrationItem.builder().id("item-1").event(event).registration(registration)
                .eventCategory(EventCategory.builder().id("single").build()).sortOrder(1)
                .souvenirJson(List.copyOf(souvenirs)).amount(new BigDecimal("40000")).build();
    }

    private static EventCategorySouvenir mapping(Souvenir souvenir) {
        EventCategorySouvenir mapping = mock(EventCategorySouvenir.class);
        when(mapping.getSouvenir()).thenReturn(souvenir);
        return mapping;
    }

    private static Souvenir souvenir(String id, Long order, String sizes, boolean active) {
        Souvenir souvenir = mock(Souvenir.class);
        lenient().when(souvenir.getId()).thenReturn(id);
        lenient().when(souvenir.getOrder()).thenReturn(order);
        lenient().when(souvenir.getSizes()).thenReturn(sizes);
        lenient().when(souvenir.getIsActive()).thenReturn(active);
        return souvenir;
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
