package kr.co.teambrain.marvelrun.user.payment.command.application;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.PaymentProcessLog;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentProcessLogCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.generator.PaymentOrderIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 기존 최초 주문 생성기를 재사용하는 추가 주문의 목적·금액·대상을 검증한다. */
class PaymentAdditionalCreatorTest {
    private PaymentCommandRepository payments;
    private PaymentProcessLogCommandRepository logs;
    private PaymentOrderIdGenerator orderIds;
    private PaymentCreator creator;

    /** 저장 결과로 전달된 실제 엔티티를 반환하고 새 주문번호를 제공한다. */
    @BeforeEach
    void setUp() {
        payments = mock(PaymentCommandRepository.class);
        logs = mock(PaymentProcessLogCommandRepository.class);
        orderIds = mock(PaymentOrderIdGenerator.class);
        creator = new PaymentCreator(payments, logs, orderIds);
        when(payments.save(any(Payment.class))).thenAnswer(call -> call.getArgument(0));
        when(orderIds.generate()).thenReturn("additional-order-1");
    }

    /** 개인 추가 주문은 부족액만 사용하며 기존 순납부액을 변경하지 않는다. */
    @Test
    void createsPersonalAdditionalOrderForOnlyOutstandingAmount() {
        Registration registration = registration();
        Payment payment = creator.createAdditionalPayment(
                registration, new BigDecimal("10000"), "correlation");
        assertThat(payment.getRegistration()).isSameAs(registration);
        assertThat(payment.getOrganization()).isNull();
        assertThat(payment.getPurpose()).isEqualTo(PaymentPurpose.ADDITIONAL_PAYMENT);
        assertThat(payment.getProcessStatus()).isEqualTo(PaymentProcessStatus.READY);
        assertThat(payment.getAmount()).isEqualByComparingTo("10000");
        assertThat(payment.getOrderId()).isEqualTo("additional-order-1");
        assertThat(payment.getConfirmIdempotencyKey()).isNotBlank();
        assertThat(registration.getPaidAmount()).isEqualByComparingTo("30000");
        verify(logs).save(any(PaymentProcessLog.class));
    }

    /** 계약금액 전체를 추가 주문 금액으로 잘못 전달하면 저장 전에 거절한다. */
    @Test
    void rejectsPersonalAmountDifferentFromCurrentBalance() {
        Registration registration = registration();
        assertThatThrownBy(() -> creator.createAdditionalPayment(
                registration, new BigDecimal("40000"), "correlation"))
                .isInstanceOf(CustomException.class);
        verifyNoInteractions(payments, logs);
    }

    /** 단체 추가 주문은 단체를 직접 대상으로 유지한다. */
    @Test
    void createsOrganizationAdditionalOrder() {
        Event event = mock(Event.class);
        when(event.getNameKr()).thenReturn("대회");
        Organization organization = mock(Organization.class);
        when(organization.getId()).thenReturn("org");
        when(organization.getEvent()).thenReturn(event);
        when(organization.getGroupName()).thenReturn("단체");
        Payment payment = creator.createAdditionalPayment(
                organization, new BigDecimal("20000"), "correlation");
        assertThat(payment.getRegistration()).isNull();
        assertThat(payment.getOrganization()).isSameAs(organization);
        assertThat(payment.getPurpose()).isEqualTo(PaymentPurpose.ADDITIONAL_PAYMENT);
        assertThat(payment.getAmount()).isEqualByComparingTo("20000");
        verify(logs).save(any(PaymentProcessLog.class));
    }

    /** 주문명과 금액 계산에 필요한 신청을 구성한다. */
    private Registration registration() {
        Event event = mock(Event.class);
        when(event.getNameKr()).thenReturn("대회");
        EventCategory category = mock(EventCategory.class);
        when(category.getName()).thenReturn("종목");
        return Registration.builder().id("registration").event(event).eventCategory(category)
                .contractAmount(new BigDecimal("40000"))
                .paidAmount(new BigDecimal("30000"))
                .status(RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED).build();
    }
}