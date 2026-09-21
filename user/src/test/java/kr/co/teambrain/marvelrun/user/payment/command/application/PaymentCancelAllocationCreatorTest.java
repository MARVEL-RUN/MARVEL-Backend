package kr.co.teambrain.marvelrun.user.payment.command.application;

import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.PaymentCancel;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentCancelAllocationCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.PaymentAllocation;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.PaymentCancelAllocation;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentCancelAllocationCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentCancelAllocationTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** 취소 금액의 참가자별 귀속과 원결제 보존 계약을 검증한다. */
class PaymentCancelAllocationCreatorTest {
    private PaymentCancelAllocationCommandRepository repository;
    private PaymentCancelAllocationCreator creator;
    private Payment payment;

    /** 중첩 Mock stubbing 없이 실제 금융 엔티티와 저장 대역을 준비한다. */
    @BeforeEach
    void setUp() {
        repository = mock(PaymentCancelAllocationCommandRepository.class);
        creator = new PaymentCancelAllocationCreator(repository);
        when(repository.saveAll(anyList())).thenAnswer(call -> call.getArgument(0));
        payment = Payment.builder().id("payment")
                .amount(money("70000"))
                .processStatus(PaymentProcessStatus.COMPLETED).build();
    }

    /** 단체 일부 구성원의 환불만 귀속하고 원 Allocation과 납부액은 유지한다. */
    @Test
    void createsOnlyRequestedMembersRefundAllocation() {
        PaymentAllocation original = original("a", payment, "30000");
        PaymentCancel cancellation = cancellation("10000");
        List<PaymentCancelAllocation> result = creator.create(cancellation,
                List.of(new PaymentCancelAllocationTarget(original, money("10000"))));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPaymentCancel()).isSameAs(cancellation);
        assertThat(result.get(0).getOriginalAllocation()).isSameAs(original);
        assertThat(result.get(0).getAllocatedAmount()).isEqualByComparingTo("10000");
        assertThat(original.getAllocatedAmount()).isEqualByComparingTo("30000");
        assertThat(original.getRegistration().getPaidAmount()).isEqualByComparingTo("30000");
    }

    /** 한 취소가 여러 참가자에게 귀속되면 합계가 취소 총액과 같아야 한다. */
    @Test
    void createsMultipleAllocationsWithMatchingSum() {
        PaymentAllocation a = original("a", payment, "30000");
        PaymentAllocation b = original("b", payment, "40000");
        List<PaymentCancelAllocation> result = creator.create(cancellation("15000"), List.of(
                new PaymentCancelAllocationTarget(b, money("5000")),
                new PaymentCancelAllocationTarget(a, money("10000"))));
        assertThat(result).extracting(row -> row.getOriginalAllocation().getId())
                .containsExactly("a", "b");
        assertThat(result.stream().map(PaymentCancelAllocation::getAllocatedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("15000");
    }

    /** 다른 원결제의 Allocation을 이번 취소에 섞지 못하게 한다. */
    @Test
    void rejectsAllocationFromAnotherPayment() {
        Payment other = Payment.builder().id("other").build();
        PaymentAllocation original = original("a", other, "30000");
        assertInvalid(() -> creator.create(cancellation("10000"),
                List.of(new PaymentCancelAllocationTarget(original, money("10000")))));
    }

    /** 동일 원 Allocation을 중복 기입한 요청을 저장 전에 거절한다. */
    @Test
    void rejectsDuplicateOriginalAllocation() {
        PaymentAllocation original = original("a", payment, "30000");
        assertInvalid(() -> creator.create(cancellation("20000"), List.of(
                new PaymentCancelAllocationTarget(original, money("10000")),
                new PaymentCancelAllocationTarget(original, money("10000")))));
    }

    /** 취소 총액과 귀속 합계가 다르면 저장하지 않는다. */
    @Test
    void rejectsTotalMismatch() {
        PaymentAllocation original = original("a", payment, "30000");
        assertInvalid(() -> creator.create(cancellation("20000"),
                List.of(new PaymentCancelAllocationTarget(original, money("10000")))));
    }

    /** 잘못된 금액이나 원 귀속액을 초과한 배정을 차단한다. */
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "30001", "0.001"})
    void rejectsInvalidAllocatedAmount(String value) {
        PaymentAllocation original = original("a", payment, "30000");
        assertInvalid(() -> creator.create(cancellation("10000"),
                List.of(new PaymentCancelAllocationTarget(original, money(value)))));
    }

    /** 이미 귀속을 저장한 취소에 새 귀속을 덧붙이지 않는다. */
    @Test
    void rejectsSecondCreationForSameCancel() {
        when(repository.existsByPaymentCancel_Id("cancel")).thenReturn(true);
        PaymentAllocation original = original("a", payment, "30000");
        assertInvalid(() -> creator.create(cancellation("10000"),
                List.of(new PaymentCancelAllocationTarget(original, money("10000")))));
    }

    /** 종료된 취소에 새 귀속을 만들지 않는다. */
    @Test
    void rejectsCompletedCancel() {
        PaymentCancel done = PaymentCancel.builder().id("cancel").payment(payment)
                .cancelAmount(money("10000")).status(PaymentCancelStatus.DONE).build();
        PaymentAllocation original = original("a", payment, "30000");
        assertInvalid(() -> creator.create(done,
                List.of(new PaymentCancelAllocationTarget(original, money("10000")))));
    }

    /** 정합성 오류 코드와 저장 미호출을 함께 검증한다. */
    private void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CustomException.class,
                error -> assertThat(error.getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR));
        verify(repository, never()).saveAll(anyList());
    }

    /** 준비 트랜잭션에서 저장된 취소 시도를 구성한다. */
    private PaymentCancel cancellation(String amount) {
        return PaymentCancel.builder().id("cancel").payment(payment)
                .cancelAmount(money(amount)).status(PaymentCancelStatus.PROCESSING).build();
    }

    /** 과거 참가자별 결제 귀속을 실제 엔티티로 구성한다. */
    private PaymentAllocation original(String id, Payment parent, String amount) {
        Registration registration = Registration.builder().id("registration-" + id)
                .contractAmount(money(amount)).paidAmount(money(amount)).build();
        return PaymentAllocation.builder().id(id).payment(parent)
                .registration(registration).allocatedAmount(money(amount)).build();
    }

    /** 부동소수점 변환 없이 테스트 금액을 생성한다. */
    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}