package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 완료 환불의 순납부액 반영과 기존 금융 상태 판정의 연결을 검증한다.
 *
 * 중복 결과 차단과 트랜잭션 원자성은 취소 결과 서비스에서 검증한다.
 */
class RegistrationRefundAmountTest {

    /**
     * 차액 환불은 계약금액을 유지하며 순납부액을 줄인다.
     * 금융 상태는 기존 판정 메서드로 결정한다.
     */
    @Test
    void partialRefundPreservesContractAndConfirmsSettledRegistration() {
        Registration registration = registration("30000", "40000");

        registration.applySuccessfulRefund(money("10000"));

        assertThat(registration.getContractAmount())
                .isEqualByComparingTo("30000");
        assertThat(registration.getPaidAmount())
                .isEqualByComparingTo("30000");

        // 차감 메서드는 업무 상태를 임의로 완료시키지 않는다.
        assertThat(registration.getStatus())
                .isEqualTo(RegistrationStatus.PARTIAL_REFUND_REQUIRED);

        BigDecimal balance =
                registration.reconcileModificationFinancialState(
                        ReservationStatus.CONSUMED
                );

        assertThat(balance).isEqualByComparingTo("0");
        assertThat(registration.getStatus())
                .isEqualTo(RegistrationStatus.CONFIRMED);
    }

    /**
     * 여러 원결제 중 일부만 환불되면 남은 환불 필요액을 유지한다.
     */
    @Test
    void partialCompletionKeepsRemainingRefundRequired() {
        Registration registration = registration("30000", "50000");

        registration.applySuccessfulRefund(money("10000"));

        BigDecimal balance =
                registration.reconcileModificationFinancialState(
                        ReservationStatus.CONSUMED
                );

        assertThat(registration.getPaidAmount())
                .isEqualByComparingTo("40000");
        assertThat(balance).isEqualByComparingTo("-10000");
        assertThat(registration.getStatus())
                .isEqualTo(RegistrationStatus.PARTIAL_REFUND_REQUIRED);
    }

    /**
     * 이미 제거된 단체 구성원은 잔액이 남으면 취소 대기를 유지하고,
     * 전액 환불 후 기존 판정으로 취소 완료 상태가 된다.
     */
    @Test
    void removedMemberCompletesCancellationOnlyAfterAllRefunds() {
        Organization organization = mock(Organization.class);

        Registration registration = Registration.builder()
                .organization(organization)
                .contractAmount(money("40000"))
                .paidAmount(money("40000"))
                .status(RegistrationStatus.CONFIRMED)
                .build();

        registration.removeFromOrganization();

        registration.applySuccessfulRefund(money("30000"));
        registration.reconcileModificationFinancialState(
                ReservationStatus.RELEASED
        );

        assertThat(registration.getPaidAmount())
                .isEqualByComparingTo("10000");
        assertThat(registration.getStatus())
                .isEqualTo(RegistrationStatus.CANCELLATION_PENDING);

        registration.applySuccessfulRefund(money("10000"));
        registration.reconcileModificationFinancialState(
                ReservationStatus.RELEASED
        );

        assertThat(registration.getPaidAmount())
                .isEqualByComparingTo("0");
        assertThat(registration.getContractAmount())
                .isEqualByComparingTo("0");
        assertThat(registration.getStatus())
                .isEqualTo(RegistrationStatus.CANCELED);
        assertThat(registration.getOrganization())
                .isSameAs(organization);
        assertThat(registration.isSoftDeleted()).isTrue();
    }

    /**
     * 순납부액보다 큰 차감이나 잘못된 금액은 엔티티 변경 전에 거절한다.
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-1", "40001"})
    void rejectsInvalidRefundWithoutChangingRegistration(String value) {
        Registration registration = registration("30000", "40000");
        BigDecimal refundAmount = value == null ? null : money(value);

        assertThatThrownBy(
                () -> registration.applySuccessfulRefund(refundAmount)
        )
                .isInstanceOfSatisfying(
                        CustomException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(
                                        ErrorCode.REGISTRATION_FINANCIAL_STATE_INVALID
                                )
                );

        assertThat(registration.getPaidAmount())
                .isEqualByComparingTo("40000");
        assertThat(registration.getContractAmount())
                .isEqualByComparingTo("30000");
        assertThat(registration.getStatus())
                .isEqualTo(RegistrationStatus.PARTIAL_REFUND_REQUIRED);
    }

    /**
     * 원래 순납부액이 누락되거나 음수인 경우 보정하지 않고 거절한다.
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"-1"})
    void rejectsInvalidExistingPaidAmount(String value) {
        BigDecimal paidAmount = value == null ? null : money(value);

        Registration registration = Registration.builder()
                .contractAmount(money("30000"))
                .paidAmount(paidAmount)
                .build();

        assertThatThrownBy(
                () -> registration.applySuccessfulRefund(money("1000"))
        )
                .isInstanceOfSatisfying(
                        CustomException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(
                                        ErrorCode.REGISTRATION_FINANCIAL_STATE_INVALID
                                )
                );

        assertThat(registration.getPaidAmount()).isEqualTo(paidAmount);
    }

    /**
     * 환불 반영에 필요한 금액과 상태를 가진 실제 엔티티를 만든다.
     */
    private Registration registration(String contract, String paid) {
        return Registration.builder()
                .contractAmount(money(contract))
                .paidAmount(money(paid))
                .status(RegistrationStatus.PARTIAL_REFUND_REQUIRED)
                .build();
    }

    /**
     * 부동소수점 변환 없이 테스트 금액을 생성한다.
     */
    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}