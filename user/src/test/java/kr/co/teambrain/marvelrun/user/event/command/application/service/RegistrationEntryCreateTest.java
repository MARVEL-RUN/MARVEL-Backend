package kr.co.teambrain.marvelrun.user.event.command.application.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import kr.co.teambrain.marvelrun.common.discount.model.RegistrationPriceCalculationSnapshot;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ProductType;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.RegistrationCapacityService;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.ReservationReleaseService;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.discount.service.DiscountPolicyResolver;
import kr.co.teambrain.marvelrun.user.discount.service.LinkDiscountEligibilityService;
import kr.co.teambrain.marvelrun.user.discount.service.RegistrationPriceCalculationRecorder;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationCreateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationEntryDraft;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationItemDraft;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.*;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.*;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationApplyValidator;
import kr.co.teambrain.marvelrun.user.event.command.repository.*;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyService;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentAllocationCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.never;

/** 신청 저장과 같은 트랜잭션에서 검증이 정한 구매 줄·참가 항목을 저장하고 줄 가격을 더하는지 확인한다. */
@ExtendWith(MockitoExtension.class)
class RegistrationEntryCreateTest {
    @Mock private RegistrationPriceCalculationRecorder priceCalculations;
    @Mock private RegistrationActionPolicyService actionPolicies;
    @Mock private RegistrationStartGroupResolver startGroups;
    @Spy private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    @Mock private RegistrationCapacityService capacity;
    @Mock private ReservationReleaseService release;
    @Mock private EventCommandRepository events;
    @Mock private EventCategoryCommandRepository categories;
    @Mock private RegistrationItemCommandRepository registrationItems;
    @Mock private RegistrationEntryCommandRepository registrationEntries;
    @Mock private RegistrationCommandRepository registrations;
    @Mock private RegistrationApplyValidator personalValidator;
    @Mock private RegistrationPricingService pricing;
    @Mock private DiscountPolicyResolver discountPolicies;
    @Mock private LinkDiscountEligibilityService discountEligibility;
    @Mock private PaymentCreator payments;
    @Mock private PaymentAllocationCreator allocations;
    @Mock private ServerTimeProvider time;
    @Mock private PaymentCommandRepository paymentRepository;
    @InjectMocks private RegistrationCommandService service;
    private final Event event = Event.builder().id("event").build();
    private final EventCategory category = EventCategory.builder().id("category").build();
    private final EventCategory pack = EventCategory.builder().id("package").productType(ProductType.PACKAGE).build();
    private final EventCategory avengers = EventCategory.builder().id("avengers").productType(ProductType.SINGLE)
            .parentCategoryId("package").build();
    private final EventCategory xmen = EventCategory.builder().id("xmen").productType(ProductType.SINGLE)
            .parentCategoryId("package").build();
    private static final List<SouvenirJson> KIT = List.of(new SouvenirJson("kit", "FREE"));
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 5, 12, 0);

    /** 단품 2개와 패키지 1개를 줄 순서대로 저장하고, 항목은 자기 줄에 묶는다. 신청 행은 첫 줄이 대표다. */
    @Test
    void savesItemsInOrderAndBindsEntriesToTheirItem() {
        RegistrationCreateRequest request = request();
        prepare(request, List.of(
                single(avengers, "아이1"),
                single(xmen, "아이2"),
                new RegistrationItemDraft(pack, KIT, List.of(entry(avengers, "엄마"), entry(xmen, "아빠")))));
        price(avengers, "40000");
        price(xmen, "40000");
        price(pack, "75000");

        service.register("event", request);

        ArgumentCaptor<Registration> saved = ArgumentCaptor.forClass(Registration.class);
        verify(registrations).save(saved.capture());
        assertThat(saved.getValue().getEventCategory()).isSameAs(avengers);
        assertThat(saved.getValue().getSouvenirJson()).isEqualTo(KIT);
        assertThat(saved.getValue().getContractAmount()).isEqualByComparingTo("155000");

        ArgumentCaptor<List<RegistrationItem>> items = ArgumentCaptor.forClass(List.class);
        verify(registrationItems).saveAll(items.capture());
        assertThat(items.getValue()).extracting(RegistrationItem::getSortOrder, item -> item.getEventCategory().getId())
                .containsExactly(tuple(1, "avengers"), tuple(2, "xmen"), tuple(3, "package"));
        assertThat(items.getValue()).extracting(RegistrationItem::getAmount)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("40000"), new BigDecimal("40000"), new BigDecimal("75000"));
        assertThat(items.getValue()).allSatisfy(item -> assertThat(item.getRegistration()).isSameAs(saved.getValue()));

        ArgumentCaptor<List<RegistrationEntry>> entries = ArgumentCaptor.forClass(List.class);
        verify(registrationEntries).saveAll(entries.capture());
        assertThat(entries.getValue()).hasSize(4);
        assertThat(entries.getValue()).allSatisfy(entry -> {
            assertThat(entry.getRegistration()).isSameAs(saved.getValue());
            assertThat(entry.getEvent()).isSameAs(event);
            assertThat(entry.getBibNumber()).isNull();
        });
        assertThat(entries.getValue()).extracting(entry -> entry.getRegistrationItem().getSortOrder(), RegistrationEntry::getName)
                .containsExactly(tuple(1, "아이1"), tuple(2, "아이2"), tuple(3, "엄마"), tuple(3, "아빠"));

        // 줄 저장 → 항목 저장 순서이고, 둘 다 신청 저장 뒤다.
        InOrder order = inOrder(registrations, registrationItems, registrationEntries, priceCalculations, capacity);
        order.verify(registrations).save(any(Registration.class));
        order.verify(registrationItems).saveAll(anyList());
        order.verify(registrationEntries).saveAll(anyList());
        order.verify(priceCalculations).recordRegistrationPriceCalculation(any(), any(), any());
        order.verify(capacity).holdAndCloseIfFull(eq(event), anyList(), eq(NOW));
    }

    /** 같은 상품을 두 개 사면 줄이 두 개이고 항목도 줄마다 따로 저장된다. */
    @Test
    void sameProductTwiceCreatesTwoItems() {
        RegistrationCreateRequest request = request();
        prepare(request, List.of(single(avengers, "첫째"), single(avengers, "둘째")));
        price(avengers, "40000");

        service.register("event", request);

        ArgumentCaptor<List<RegistrationItem>> items = ArgumentCaptor.forClass(List.class);
        verify(registrationItems).saveAll(items.capture());
        assertThat(items.getValue()).extracting(RegistrationItem::getSortOrder).containsExactly(1, 2);
        ArgumentCaptor<List<RegistrationEntry>> entries = ArgumentCaptor.forClass(List.class);
        verify(registrationEntries).saveAll(entries.capture());
        assertThat(entries.getValue()).extracting(RegistrationEntry::getRegistrationItem)
                .doesNotHaveDuplicates().containsExactlyElementsOf(items.getValue());
        // 상품 줄 가격은 생년월일 없이(연령 할인 없이) 계산한다.
        verify(pricing, times(2)).calculateProductPrice(eq(event), eq(avengers), isNull(), anyList());
    }

    /** 기록하는 가격은 줄 가격의 합이고 계약금액과 같다. 할인 내역은 줄 순서대로 잇는다. */
    @Test
    void recordedPriceIsSumOfItemPrices() {
        RegistrationCreateRequest request = request();
        prepare(request, List.of(single(avengers, "아이"),
                new RegistrationItemDraft(pack, KIT, List.of(entry(avengers, "엄마"), entry(xmen, "아빠")))));
        DiscountModels.AppliedDiscount discount = mock(DiscountModels.AppliedDiscount.class);
        when(pricing.calculateProductPrice(eq(event), eq(avengers), isNull(), anyList()))
                .thenReturn(new DiscountModels.PriceResult(DiscountModels.Failure.NONE, new BigDecimal("40000"),
                        new BigDecimal("36000"), BigDecimal.ZERO, List.of(discount)));
        price(pack, "75000");

        service.register("event", request);

        ArgumentCaptor<DiscountModels.PriceResult> recorded = ArgumentCaptor.forClass(DiscountModels.PriceResult.class);
        ArgumentCaptor<Registration> saved = ArgumentCaptor.forClass(Registration.class);
        verify(priceCalculations).recordRegistrationPriceCalculation(saved.capture(), recorded.capture(),
                eq(RegistrationPriceCalculationSnapshot.Reason.REGISTRATION_CREATED));
        assertThat(recorded.getValue().baseAmount()).isEqualByComparingTo("115000");
        assertThat(recorded.getValue().finalAmount()).isEqualByComparingTo("111000");
        assertThat(recorded.getValue().appliedDiscounts()).containsExactly(discount);
        assertThat(saved.getValue().getContractAmount()).isEqualByComparingTo(recorded.getValue().finalAmount());
    }

    /** 참가 항목이 없는 일반 종목은 줄·항목 저장소를 부르지 않는다. */
    @Test
    void skipsItemsWhenNoProduct() {
        RegistrationCreateRequest request = request();
        when(startGroups.resolveRegistrationStartGroups(anyList())).thenAnswer(invocation -> {
            List<RegistrationStartGroupResolver.Input> inputs = invocation.getArgument(0);
            return inputs.stream().map(input -> new RegistrationStartGroupResolver.Selection(
                    input.startGroupId(), input.unselected())).toList();
        });
        when(time.currentDateTime()).thenReturn(NOW);
        when(personalValidator.validate("event", request, NOW, false))
                .thenReturn(new RegistrationCreateContext(event, category, List.of(), List.of(), false));
        // 일반 종목은 지금처럼 생년월일로 연령 할인을 보는 가격 계산을 쓴다.
        when(pricing.calculateRegistrationPrice(eq(event), eq(category), eq("1990-01-01"), eq(false), isNull(), anyList()))
                .thenReturn(new DiscountModels.PriceResult(DiscountModels.Failure.NONE, BigDecimal.TEN,
                        BigDecimal.TEN, BigDecimal.ZERO, List.of()));
        when(registrations.save(any(Registration.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(payments.createInitialPayment(any(Registration.class), anyString())).thenReturn(mock(Payment.class));

        service.register("event", request);

        verify(registrationItems, never()).saveAll(any());
        verify(registrationEntries, never()).saveAll(any());
    }

    /** 대회 정책의 같은 신청자 허용 값을 신청 행에 복사한다. */
    @Test
    void copiesDuplicateAllowedFromContext() {
        RegistrationCreateRequest request = request();
        prepare(request, List.of(single(avengers, "아이")), true);
        price(avengers, "40000");

        service.register("event", request);

        ArgumentCaptor<Registration> saved = ArgumentCaptor.forClass(Registration.class);
        verify(registrations).save(saved.capture());
        assertThat(saved.getValue().isDuplicateAllowed()).isTrue();
    }

    private void prepare(RegistrationCreateRequest request, List<RegistrationItemDraft> items) {
        prepare(request, items, false);
    }

    private void prepare(RegistrationCreateRequest request, List<RegistrationItemDraft> items, boolean duplicateAllowed) {
        when(startGroups.resolveRegistrationStartGroups(anyList())).thenAnswer(invocation -> {
            List<RegistrationStartGroupResolver.Input> inputs = invocation.getArgument(0);
            return inputs.stream().map(input -> new RegistrationStartGroupResolver.Selection(
                    input.startGroupId(), input.unselected())).toList();
        });
        when(time.currentDateTime()).thenReturn(NOW);
        RegistrationItemDraft first = items.getFirst();
        when(personalValidator.validate("event", request, NOW, false))
                .thenReturn(new RegistrationCreateContext(event, first.product(), first.souvenirs(), items, duplicateAllowed));
        when(registrations.save(any(Registration.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(payments.createInitialPayment(any(Registration.class), anyString())).thenReturn(mock(Payment.class));
    }

    private void price(EventCategory product, String amount) {
        lenient().when(pricing.calculateProductPrice(eq(event), eq(product), isNull(), anyList()))
                .thenReturn(new DiscountModels.PriceResult(DiscountModels.Failure.NONE, new BigDecimal(amount),
                        new BigDecimal(amount), BigDecimal.ZERO, List.of()));
    }

    private static RegistrationItemDraft single(EventCategory product, String name) {
        return new RegistrationItemDraft(product, KIT, List.of(entry(product, name)));
    }

    private static RegistrationEntryDraft entry(EventCategory category, String name) {
        return new RegistrationEntryDraft(category, name, "010-1111-1111", "1990-01-01", GenderClass.M);
    }

    private RegistrationCreateRequest request() {
        return new RegistrationCreateRequest("category", List.of(), "TestPassword1!", "신청자", "010-0000-0000",
                "1990-01-01", GenderClass.M, "주소", "상세", false, null, null, null,
                true, false, false, "test@example.com", "group", false);
    }
}
