package kr.co.teambrain.marvelrun.user.event.command.application.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.RegistrationCapacityService;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.ReservationReleaseService;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.discount.service.DiscountPolicyResolver;
import kr.co.teambrain.marvelrun.user.discount.service.LinkDiscountEligibilityService;
import kr.co.teambrain.marvelrun.user.discount.service.RegistrationPriceCalculationRecorder;
import kr.co.teambrain.marvelrun.user.event.command.application.context.OrgRegistrationCreateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationCreateContext;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationEntryDraft;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.*;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationEntry;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.*;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.*;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.OrgRegistrationApplyValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationApplyValidator;
import kr.co.teambrain.marvelrun.user.event.command.repository.*;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationEntryCommandRepository;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyService;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentAllocationCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.never;

/** 신청 저장과 같은 트랜잭션에서 검증이 정한 참가 항목만 저장하는지 확인한다. */
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
    private final EventCategory avengers = EventCategory.builder().id("avengers").parentCategoryId("category").build();
    private final EventCategory xmen = EventCategory.builder().id("xmen").parentCategoryId("category").build();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 5, 12, 0);

    /** 검증이 돌려준 참가 항목을 저장한 신청에 묶어 저장한다. */
    @Test
    void savesEntriesBoundToSavedRegistration() {
        List<RegistrationEntryDraft> drafts = List.of(
                new RegistrationEntryDraft(avengers, "받는사람1", "010-1111-1111", "1990-01-01", GenderClass.M),
                new RegistrationEntryDraft(xmen, "받는사람2", "010-2222-2222", "1991-01-01", GenderClass.F));
        RegistrationCreateRequest request = request();
        prepare(request, drafts);

        service.register("event", request);

        ArgumentCaptor<Registration> saved = ArgumentCaptor.forClass(Registration.class);
        verify(registrations).save(saved.capture());
        ArgumentCaptor<List<RegistrationEntry>> entries = ArgumentCaptor.forClass(List.class);
        verify(registrationEntries).saveAll(entries.capture());
        assertThat(entries.getValue()).hasSize(2);
        assertThat(entries.getValue()).allSatisfy(entry -> {
            assertThat(entry.getRegistration()).isSameAs(saved.getValue());
            assertThat(entry.getEvent()).isSameAs(event);
            assertThat(entry.getBibNumber()).isNull();
        });
        assertThat(entries.getValue()).extracting(RegistrationEntry::getEventCategory, RegistrationEntry::getName)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(avengers, "받는사람1"),
                        org.assertj.core.groups.Tuple.tuple(xmen, "받는사람2"));
    }

    /** 참가 항목이 없는 상품(기존 대회)은 항목 저장소를 부르지 않는다. */
    @Test
    void skipsEntriesWhenProductHasNone() {
        RegistrationCreateRequest request = request();
        prepare(request, List.of());

        service.register("event", request);

        verify(registrationEntries, never()).saveAll(any());
    }

    private void prepare(RegistrationCreateRequest request, List<RegistrationEntryDraft> drafts) {
        when(startGroups.resolveRegistrationStartGroups(anyList())).thenAnswer(invocation -> {
            List<RegistrationStartGroupResolver.Input> inputs = invocation.getArgument(0);
            return inputs.stream().map(input -> new RegistrationStartGroupResolver.Selection(
                    input.startGroupId(), input.unselected())).toList();
        });
        when(time.currentDateTime()).thenReturn(NOW);
        when(personalValidator.validate("event", request, NOW))
                .thenReturn(new RegistrationCreateContext(event, category, List.of(), drafts));
        when(pricing.calculateRegistrationPrice(eq(event), eq(category), eq(request.birth()), eq(false), isNull(), anyList()))
                .thenReturn(new DiscountModels.PriceResult(DiscountModels.Failure.NONE, BigDecimal.TEN, BigDecimal.TEN,
                        BigDecimal.ZERO, List.of()));
        when(registrations.save(any(Registration.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(payments.createInitialPayment(any(Registration.class), anyString())).thenReturn(mock(Payment.class));
    }

    private RegistrationCreateRequest request() {
        return new RegistrationCreateRequest("category", List.of(), "TestPassword1!", "신청자", "010-0000-0000",
                "1990-01-01", GenderClass.M, "주소", "상세", false, null, null, null,
                true, false, false, "test@example.com", "group", false);
    }
}
