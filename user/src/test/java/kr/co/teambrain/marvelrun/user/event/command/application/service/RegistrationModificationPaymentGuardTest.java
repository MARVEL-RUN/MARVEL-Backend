package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.payment.command.application.PaymentRefundConflictGuard;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentCommandRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 신청 수정의 Payment 충돌 차단과 기존 금융 정보 보존을 검증한다.
 *
 * 실제 잠금 경합과 트랜잭션 롤백은 DB 테스트에서 확인한다.
 */
class RegistrationModificationPaymentGuardTest {

    private final PaymentCommandRepository repository =
            mock(PaymentCommandRepository.class);

    private final PaymentRefundConflictGuard refundGuard =
            mock(PaymentRefundConflictGuard.class);

    private final RegistrationModificationPaymentGuard guard =
            new RegistrationModificationPaymentGuard(repository, refundGuard);

    /** 분리된 충돌 검증만 호출하면 READY 상태 변경이나 flush가 없어야 한다. */
    @Test
    void validationAloneDoesNotInvalidateReady() {
        Payment ready = payment("p1", PaymentProcessStatus.READY);
        guard.validateLockedPayments(List.of(ready));
        assertThat(ready.getProcessStatus()).isEqualTo(PaymentProcessStatus.READY);
        verifyNoInteractions(repository);
    }

    /**
     * READY 주문만 무효화하고 기존 주문 금액은 유지한다.
     */
    @Test
    void invalidatesReadyWithoutChangingAmount() {
        Payment ready = payment("p1", PaymentProcessStatus.READY);

        guard.prepareLockedPayments(List.of(ready));

        assertThat(ready.getProcessStatus())
                .isEqualTo(PaymentProcessStatus.INVALIDATED);

        assertThat(ready.getAmount())
                .isEqualByComparingTo("50000");

        verify(repository).flush();
    }

    /**
     * 완료·실패·무효화 주문은 기존 상태와 금액을 유지한다.
     */
    @ParameterizedTest
    @EnumSource(
            value = PaymentProcessStatus.class,
            names = {"COMPLETED", "FAILED", "INVALIDATED"}
    )
    void preservesFinishedPayment(PaymentProcessStatus status) {
        Payment payment = payment("p1", status);

        guard.prepareLockedPayments(List.of(payment));

        assertThat(payment.getProcessStatus()).isEqualTo(status);
        assertThat(payment.getAmount()).isEqualByComparingTo("50000");
    }

    /**
     * 충돌 주문이 있으면 앞에 있는 READY 주문도 변경하지 않는다.
     */
    @ParameterizedTest
    @EnumSource(
            value = PaymentProcessStatus.class,
            names = {"CONFIRMING", "UNKNOWN"}
    )
    void rejectsConflictBeforeInvalidatingOtherPayments(
            PaymentProcessStatus status
    ) {
        Payment ready = payment("p1", PaymentProcessStatus.READY);
        Payment conflicting = payment("p2", status);

        assertThatThrownBy(
                () -> guard.prepareLockedPayments(
                        List.of(ready, conflicting)
                )
        )
                .isInstanceOf(CustomException.class)
                .satisfies(exception ->
                        assertThat(
                                ((CustomException) exception).getErrorCode()
                        ).isEqualTo(
                                ErrorCode.REGISTRATION_MODIFICATION_PAYMENT_CONFLICT
                        )
                );

        assertThat(ready.getProcessStatus())
                .isEqualTo(PaymentProcessStatus.READY);

        assertThat(conflicting.getProcessStatus()).isEqualTo(status);

        verify(repository, never()).flush();
    }

    /**
     * 개인 잠금 조회는 대회와 신청 식별자를 전달한다.
     */
    @Test
    void locksPersonalPayments() {
        Payment payment = payment("p1", PaymentProcessStatus.READY);
        List<Payment> payments = List.of(payment);

        when(repository.findAllForPersonalModificationForUpdate(
                "event", "registration"
        )).thenReturn(payments);

        assertThat(guard.lockPersonal("event", "registration"))
                .isSameAs(payments);
    }

    /**
     * 단체 잠금 조회는 대회와 단체 식별자를 전달한다.
     */
    @Test
    void locksOrganizationPayments() {
        Payment payment = payment("p1", PaymentProcessStatus.COMPLETED);
        List<Payment> payments = List.of(payment);

        when(repository.findAllForOrganizationModificationForUpdate(
                "event", "organization"
        )).thenReturn(payments);

        assertThat(guard.lockOrganization("event", "organization"))
                .isSameAs(payments);
    }

    /**
     * 관련 주문이 없으면 무효화 작업도 수행하지 않는다.
     */
    @Test
    void acceptsEmptyPaymentList() {
        guard.prepareLockedPayments(List.of());

        verifyNoInteractions(repository);
    }

    /** 진행 중 환불을 확인하면 앞에 있는 READY 주문도 무효화하지 않는다. */
    @Test
    void refundConflictPreventsReadyInvalidation() {
        Payment ready = payment("p1", PaymentProcessStatus.READY);
        List<Payment> payments = List.of(ready);
        doThrow(new CustomException(ErrorCode.PAYMENT_CANCEL_CONFLICT)).when(refundGuard).validate(payments);
        assertThatThrownBy(() -> guard.prepareLockedPayments(payments))
                .isInstanceOfSatisfying(CustomException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_CANCEL_CONFLICT));
        assertThat(ready.getProcessStatus()).isEqualTo(PaymentProcessStatus.READY);
        verify(repository, never()).flush();
    }

    /**
     * 테스트에 사용할 실제 Payment Entity를 구성한다.
     */
    private Payment payment(
            String id,
            PaymentProcessStatus status
    ) {
        return Payment.builder()
                .id(id)
                .amount(new BigDecimal("50000"))
                .processStatus(status)
                .build();
    }
}