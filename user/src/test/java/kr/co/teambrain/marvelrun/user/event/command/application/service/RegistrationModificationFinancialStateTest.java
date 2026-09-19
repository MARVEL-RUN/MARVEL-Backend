package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 계약금액 변화가 아니라 실제 순결제금액과의 차이로 상태를 결정하는지 검증한다.
 */
class RegistrationModificationFinancialStateTest {

    /**
     * 최초 미결제 가격이 내려가도 환불 상태로 처리하지 않는다.
     */
    @Test
    void unpaidPriceReductionStillRequiresInitialPayment() {
        Registration registration = registration("40000", "0");

        BigDecimal balance =
                registration.reconcileModificationFinancialState(
                        ReservationStatus.HELD
                );

        assertThat(balance).isEqualByComparingTo("40000");
        assertThat(registration.getStatus())
                .isEqualTo(RegistrationStatus.PAYMENT_PENDING);
    }

    /**
     * 기존 확정 참가자의 부족 금액을 추가 결제로 표시한다.
     */
    @Test
    void consumedRegistrationRequiresAdditionalPayment() {
        Registration registration = registration("60000", "50000");

        BigDecimal balance =
                registration.reconcileModificationFinancialState(
                        ReservationStatus.CONSUMED
                );

        assertThat(balance).isEqualByComparingTo("10000");
        assertThat(registration.getStatus())
                .isEqualTo(RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED);
    }

    /**
     * 기존 순결제금액이 새 계약금액보다 크면 부분환불 대기 상태가 된다.
     */
    @Test
    void lowerContractRequiresPartialRefund() {
        Registration registration = registration("40000", "50000");

        BigDecimal balance =
                registration.reconcileModificationFinancialState(
                        ReservationStatus.CONSUMED
                );

        assertThat(balance).isEqualByComparingTo("-10000");
        assertThat(registration.getPaidAmount())
                .isEqualByComparingTo("50000");
        assertThat(registration.getStatus())
                .isEqualTo(RegistrationStatus.PARTIAL_REFUND_REQUIRED);
    }

    /**
     * 금액이 일치하면 참가 확정 상태를 유지한다.
     */
    @Test
    void equalAmountsAreConfirmed() {
        Registration registration = registration("50000", "50000");

        BigDecimal balance =
                registration.reconcileModificationFinancialState(
                        ReservationStatus.CONSUMED
                );

        assertThat(balance).isEqualByComparingTo("0");
        assertThat(registration.getStatus())
                .isEqualTo(RegistrationStatus.CONFIRMED);
    }

    /**
     * 순결제금액이 0이어도 기존 확정 예약을 최초 신청으로 오인하지 않는다.
     */
    @Test
    void consumedWithZeroPaidIsStillAdditionalPayment() {
        Registration registration = registration("40000", "0");

        registration.reconcileModificationFinancialState(
                ReservationStatus.CONSUMED
        );

        assertThat(registration.getStatus())
                .isEqualTo(RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED);
    }

    /**
     * 제거된 유료 참가자는 환불 전까지 취소 후속 처리 대기 상태이다.
     */
    @Test
    void removedPaidRegistrationRequiresCancellationRefund() {
        Registration registration = Registration.builder()
                .organization(mock(Organization.class))
                .contractAmount(new BigDecimal("50000"))
                .paidAmount(new BigDecimal("50000"))
                .build();

        registration.removeFromOrganization();

        BigDecimal balance =
                registration.reconcileModificationFinancialState(
                        ReservationStatus.RELEASED
                );

        assertThat(balance).isEqualByComparingTo("-50000");
        assertThat(registration.getStatus())
                .isEqualTo(RegistrationStatus.CANCELLATION_PENDING);
    }

    /**
     * 최초 홀딩과 순결제금액의 불일치를 임의로 보정하지 않는다.
     */
    @Test
    void rejectsPaidAmountOnHeldReservation() {
        Registration registration = registration("50000", "10000");

        assertThatThrownBy(
                () -> registration.reconcileModificationFinancialState(
                        ReservationStatus.HELD
                )
        ).isInstanceOf(CustomException.class);
    }

    /**
     * 검증에 필요한 금액을 가진 실제 Entity를 구성한다.
     */
    private Registration registration(String contract, String paid) {
        return Registration.builder()
                .contractAmount(new BigDecimal(contract))
                .paidAmount(new BigDecimal(paid))
                .build();
    }
}