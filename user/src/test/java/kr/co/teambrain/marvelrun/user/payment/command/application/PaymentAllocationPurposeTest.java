package kr.co.teambrain.marvelrun.user.payment.command.application;

import java.math.BigDecimal;
import java.util.List;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentAllocationCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.PaymentAllocation;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentAllocationCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentAllocationTarget;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyList;

/** 혼합 결제의 참가자별 목적 고정과 기존 귀속 호환성을 검증한다. */
class PaymentAllocationPurposeTest {
    /** 최초·추가 금액을 한 주문에 저장하면서 귀속의 목적을 각각 보존한다. */
    @Test
    void mixedOrderPreservesEachPurpose() {
        Organization organization = Organization.builder().id("org").build();
        Registration initial = Registration.builder().id("initial").organization(organization).build();
        Registration additional = Registration.builder().id("additional").organization(organization).build();
        Payment payment = Payment.builder().id("payment").organization(organization)
                .purpose(PaymentPurpose.MIXED_PAYMENT).amount(new BigDecimal("50000")).build();
        PaymentAllocationCommandRepository repository = mock(PaymentAllocationCommandRepository.class);
        when(repository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        PaymentAllocationCreator creator = new PaymentAllocationCreator(repository);

        List<PaymentAllocation> result = creator.create(payment, List.of(
                new PaymentAllocationTarget(initial, new BigDecimal("30000"), PaymentPurpose.REGISTRATION_TRY),
                new PaymentAllocationTarget(additional, new BigDecimal("20000"), PaymentPurpose.ADDITIONAL_PAYMENT)));

        assertThat(result).extracting(PaymentAllocation::effectivePurpose)
                .containsExactly(PaymentPurpose.REGISTRATION_TRY, PaymentPurpose.ADDITIONAL_PAYMENT);
        assertThat(result).extracting(PaymentAllocation::getAllocatedAmount)
                .containsExactly(new BigDecimal("30000"), new BigDecimal("20000"));
    }

    /** 기존 단일 목적 주문의 NULL 귀속은 부모 목적을 따라 해석한다. */
    @Test
    void legacySinglePurposeAllocationRemainsReadable() {
        for (PaymentPurpose purpose : List.of(PaymentPurpose.REGISTRATION_TRY, PaymentPurpose.ADDITIONAL_PAYMENT)) {
            Payment payment = Payment.builder().purpose(purpose).build();
            PaymentAllocation allocation = PaymentAllocation.builder().payment(payment).build();
            assertThat(allocation.effectivePurpose()).isEqualTo(purpose);
        }
    }

    /** 혼합 주문에서 목적이 누락되면 현재 신청 상태로 임의 추정하지 않는다. */
    @Test
    void mixedAllocationWithoutPurposeIsRejected() {
        Payment payment = Payment.builder().purpose(PaymentPurpose.MIXED_PAYMENT).build();
        PaymentAllocation allocation = PaymentAllocation.builder().payment(payment).build();
        assertThatThrownBy(allocation::effectivePurpose).isInstanceOf(CustomException.class);
    }

    /** 단일 목적 주문과 모순되는 귀속 목적을 거절한다. */
    @Test
    void singlePurposeMismatchIsRejected() {
        Payment payment = Payment.builder().purpose(PaymentPurpose.REGISTRATION_TRY).build();
        PaymentAllocation allocation = PaymentAllocation.builder().payment(payment)
                .allocationPurpose(PaymentPurpose.ADDITIONAL_PAYMENT).build();
        assertThatThrownBy(allocation::effectivePurpose).isInstanceOf(CustomException.class);
    }

    /** 한 종류만 있는 주문을 혼합 주문으로 저장하지 않는다. */
    @Test
    void mixedOrderRequiresBothKinds() {
        Organization organization = Organization.builder().id("org").build();
        Registration registration = Registration.builder().id("r").organization(organization).build();
        Payment payment = Payment.builder().id("payment").organization(organization)
                .purpose(PaymentPurpose.MIXED_PAYMENT).amount(new BigDecimal("30000")).build();
        PaymentAllocationCommandRepository repository = mock(PaymentAllocationCommandRepository.class);
        PaymentAllocationCreator creator = new PaymentAllocationCreator(repository);
        assertThatThrownBy(() -> creator.create(payment, List.of(new PaymentAllocationTarget(
                registration, new BigDecimal("30000"), PaymentPurpose.REGISTRATION_TRY))))
                .isInstanceOf(CustomException.class);
        verifyNoInteractions(repository);
    }
}
