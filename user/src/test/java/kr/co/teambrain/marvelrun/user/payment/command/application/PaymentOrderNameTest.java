package kr.co.teambrain.marvelrun.user.payment.command.application;

import kr.co.teambrain.marvelrun.common.inheritance_enum.ProductType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationItem;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationItemCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentProcessLogCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.generator.PaymentOrderIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 최초 결제 주문명은 상품 신청이면 "대회명 - 첫 상품명 외 N건"이다. */
class PaymentOrderNameTest {

    private final PaymentCommandRepository payments = mock(PaymentCommandRepository.class);
    private final RegistrationItemCommandRepository items = mock(RegistrationItemCommandRepository.class);
    private final PaymentCreator creator = new PaymentCreator(payments, mock(PaymentProcessLogCommandRepository.class),
            mock(PaymentOrderIdGenerator.class), items);

    private final Event event = Event.builder().id("event").nameKr("마블런").build();
    private final EventCategory avengers = EventCategory.builder().id("avengers").name("5km | 어벤저스")
            .productType(ProductType.SINGLE).build();
    private final EventCategory pack = EventCategory.builder().id("package").name("5km | 3종 패키지")
            .productType(ProductType.PACKAGE).build();

    @BeforeEach
    void setUp() {
        when(payments.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** 상품이 여럿이면 첫 줄 상품명 뒤에 나머지 건수를 붙인다. */
    @Test
    void multiItemOrderNameCountsRest() {
        Registration registration = registration(avengers);
        when(items.findByRegistration_IdOrderBySortOrderAsc("r")).thenReturn(List.of(
                item(registration, 1, avengers), item(registration, 2, avengers), item(registration, 3, pack)));

        assertThat(creator.createInitialPayment(registration, "c").getOrderName())
                .isEqualTo("마블런 - 5km | 어벤저스 외 2건");
    }

    /** 상품이 하나면 지금처럼 "대회명 - 상품명"이다. */
    @Test
    void singleItemOrderNameIsProductName() {
        Registration registration = registration(pack);
        when(items.findByRegistration_IdOrderBySortOrderAsc("r")).thenReturn(List.of(item(registration, 1, pack)));

        assertThat(creator.createInitialPayment(registration, "c").getOrderName()).isEqualTo("마블런 - 5km | 3종 패키지");
    }

    /** 일반 종목 신청은 구매 줄을 읽지 않는다. */
    @Test
    void nonProductOrderNameDoesNotReadItems() {
        Registration registration = registration(EventCategory.builder().id("10km").name("10km").build());

        assertThat(creator.createInitialPayment(registration, "c").getOrderName()).isEqualTo("마블런 - 10km");
        verifyNoInteractions(items);
    }

    private Registration registration(EventCategory representative) {
        return Registration.builder().id("r").event(event).eventCategory(representative)
                .status(RegistrationStatus.PAYMENT_PENDING).contractAmount(new BigDecimal("155000"))
                .paidAmount(BigDecimal.ZERO).build();
    }

    private static RegistrationItem item(Registration registration, int order, EventCategory product) {
        return RegistrationItem.create(registration, order, product, List.of(), BigDecimal.TEN);
    }
}
