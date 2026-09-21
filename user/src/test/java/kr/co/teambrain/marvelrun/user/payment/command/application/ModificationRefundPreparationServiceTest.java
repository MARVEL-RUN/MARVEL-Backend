package kr.co.teambrain.marvelrun.user.payment.command.application;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_log.PaymentProcessType;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.PaymentCancel;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult.Refund;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.ModificationRefundPlanner;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentCancelAllocationCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.PaymentAllocation;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.PaymentProcessLog;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 저장 준비는 돈을 차감하지 않으며, 미확정 시도를 중복 생성하지 않는지 검증한다. */
class ModificationRefundPreparationServiceTest {
    private final PaymentCommandRepository payments = mock(PaymentCommandRepository.class);
    private final PaymentAllocationCommandRepository allocations = mock(PaymentAllocationCommandRepository.class);
    private final PaymentCancelCommandRepository cancellations = mock(PaymentCancelCommandRepository.class);
    private final PaymentCancelAllocationCommandRepository cancelAllocations = mock(PaymentCancelAllocationCommandRepository.class);
    private final PaymentProcessLogCommandRepository logs = mock(PaymentProcessLogCommandRepository.class);
    private final PaymentCancelAllocationCreator creator = mock(PaymentCancelAllocationCreator.class);
    private final ModificationRefundPreparationService service = new ModificationRefundPreparationService(
            payments, allocations, cancellations, cancelAllocations, logs, new ModificationRefundPlanner(), creator);

    /** 같은 부족 환불 요청이 다시 오면 기존 PROCESSING을 우회하여 새 취소를 만들지 않는다. */
    @Test
    void reservesOnceWithoutDecreasingPaidAmount() {
        Registration registration = target();
        Payment payment = original(registration);
        PaymentAllocation allocation = originalAllocation(payment, registration);
        List<PaymentCancel> saved = new ArrayList<>();
        when(payments.findAllForPersonalModificationForUpdate("event", "r")).thenReturn(List.of(payment));
        when(allocations.findAllForRefund("p")).thenReturn(List.of(allocation));
        when(cancellations.findAllByPaymentIdsForUpdate(List.of("p"))).thenAnswer(invocation -> List.copyOf(saved));
        when(cancellations.save(any(PaymentCancel.class))).thenAnswer(invocation -> {
            PaymentCancel cancellation = invocation.getArgument(0);
            ReflectionTestUtils.setField(cancellation, "id", "cancel");
            saved.add(cancellation);
            return cancellation;
        });

        List<Refund> result = service.prepare("event", null, List.of(registration));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).amount()).isEqualByComparingTo("10000");
        assertThat(saved.get(0).getStatus()).isEqualTo(PaymentCancelStatus.PROCESSING);
        assertThat(saved.get(0).getRequestedAt()).isNull();
        assertThat(saved.get(0).getIdempotencyKey()).isNotBlank();
        assertThat(registration.getPaidAmount()).isEqualByComparingTo("40000");
        ArgumentCaptor<PaymentProcessLog> captor = ArgumentCaptor.forClass(PaymentProcessLog.class);
        verify(logs).save(captor.capture());
        assertThat(captor.getValue().getProcessType()).isEqualTo(PaymentProcessType.CANCEL_PREPARED);
        assertThat(captor.getValue().getPaymentCancelId()).isEqualTo("cancel");
        assertThat(captor.getValue().getCorrelationId()).isEqualTo(result.get(0).correlationId());
        assertThatThrownBy(() -> service.prepare("event", null, List.of(registration)))
                .isInstanceOfSatisfying(CustomException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_CANCEL_CONFLICT));
        verify(cancellations, times(1)).save(any(PaymentCancel.class));
        verify(creator, times(1)).create(any(PaymentCancel.class), anyList());
    }

    /** 초과 납부가 없으면 추가 금융 원장 조회 자체를 하지 않는다. */
    @Test
    void noRefundSkipsRepositories() {
        Registration r = Registration.builder().id("r").contractAmount(new BigDecimal("40000"))
                .paidAmount(new BigDecimal("40000")).build();
        assertThat(service.prepare("event", null, List.of(r))).isEmpty();
        verifyNoInteractions(payments, allocations, cancellations, cancelAllocations, logs, creator);
    }

    /** 환불 귀속 저장 오류를 상위 트랜잭션에 전파하고 준비 완료 응답을 반환하지 않는다. */
    @Test
    void allocationFailureIsNotSwallowed() {
        Registration r = target();
        Payment p = original(r);
        PaymentAllocation allocation = originalAllocation(p, r);
        when(payments.findAllForPersonalModificationForUpdate("event", "r")).thenReturn(List.of(p));
        when(allocations.findAllForRefund("p")).thenReturn(List.of(allocation));
        when(cancellations.save(any(PaymentCancel.class))).thenAnswer(invocation -> {
            PaymentCancel c = invocation.getArgument(0);
            ReflectionTestUtils.setField(c, "id", "cancel");
            return c;
        });
        doThrow(new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR))
                .when(creator).create(any(PaymentCancel.class), anyList());
        assertThatThrownBy(() -> service.prepare("event", null, List.of(r))).isInstanceOf(CustomException.class);
        verifyNoInteractions(logs);
        verify(cancellations, never()).flush();
    }

    /** 가격 인하가 반영된 참가자를 구성한다. */
    private Registration target() {
        return Registration.builder().id("r").contractAmount(new BigDecimal("30000"))
                .paidAmount(new BigDecimal("40000")).status(RegistrationStatus.PARTIAL_REFUND_REQUIRED).build();
    }

    /** 참가자에게 실제로 승인된 원 결제를 구성한다. */
    private Payment original(Registration r) {
        return Payment.builder().id("p").registration(r).amount(new BigDecimal("40000"))
                .paymentKey("test-key").processStatus(PaymentProcessStatus.COMPLETED).build();
    }

    /** 원 승인금액 전체가 해당 참가자에게 귀속된 원장을 구성한다. */
    private PaymentAllocation originalAllocation(Payment p, Registration r) {
        return PaymentAllocation.builder().id("a").payment(p).registration(r)
                .allocatedAmount(new BigDecimal("40000")).build();
    }
}
