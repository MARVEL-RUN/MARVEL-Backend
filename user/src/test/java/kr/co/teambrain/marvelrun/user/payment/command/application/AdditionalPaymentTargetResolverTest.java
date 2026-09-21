package kr.co.teambrain.marvelrun.user.payment.command.application;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.AdditionalPaymentTargetResolver;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentAllocationTarget;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** 신청별 실제 부족액만 추가 주문에 포함하고 기존 금융정보를 보존하는지 검증한다. */
class AdditionalPaymentTargetResolverTest {
    private final AdditionalPaymentTargetResolver resolver = new AdditionalPaymentTargetResolver();

    /** 직전 가격차 대신 현재 계약금액과 실제 순납부액의 차이를 사용한다. */
    @Test
    void usesCurrentBalanceWithoutChangingRegistration() {
        Registration registration = registration("r1", "35000", "30000",
                RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED);
        List<PaymentAllocationTarget> result = resolver.resolve(List.of(registration));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).registration()).isSameAs(registration);
        assertThat(result.get(0).amount()).isEqualByComparingTo("5000");
        assertThat(registration.getContractAmount()).isEqualByComparingTo("35000");
        assertThat(registration.getPaidAmount()).isEqualByComparingTo("30000");
    }

    /** 다른 구성원의 환불 필요액을 추가 납부액에서 빼지 않는다. */
    @Test
    void doesNotOffsetRefundAgainstAnotherMembersAdditionalPayment() {
        Registration refund = registration("a", "30000", "40000",
                RegistrationStatus.PARTIAL_REFUND_REQUIRED);
        Registration additional = registration("b", "50000", "30000",
                RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED);
        Registration settled = registration("c", "30000", "30000",
                RegistrationStatus.CONFIRMED);
        List<PaymentAllocationTarget> result =
                resolver.resolve(List.of(refund, additional, settled));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).registration()).isSameAs(additional);
        assertThat(result.get(0).amount()).isEqualByComparingTo("20000");
    }

    /** 추가 납부 대상이 없으면 빈 목록을 반환하여 주문을 만들지 않게 한다. */
    @Test
    void noDebtProducesNoTargets() {
        assertThat(resolver.resolve(List.of(registration("r1", "30000", "30000",
                RegistrationStatus.CONFIRMED)))).isEmpty();
    }

    /** 최초 미결제 신청을 추가 결제 대상으로 오인하지 않는다. */
    @Test
    void rejectsInitialUnpaidRegistration() {
        Registration registration = registration("r1", "30000", "0",
                RegistrationStatus.PAYMENT_PENDING);
        assertThatThrownBy(() -> resolver.resolve(List.of(registration)))
                .isInstanceOfSatisfying(CustomException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.REGISTRATION_FINANCIAL_STATE_INVALID));
    }

    /** 같은 참가자를 두 번 포함해 주문 금액이 증가하는 요청을 거절한다. */
    @Test
    void rejectsDuplicateRegistration() {
        Registration registration = registration("r1", "40000", "30000",
                RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED);
        assertThatThrownBy(() -> resolver.resolve(List.of(registration, registration)))
                .isInstanceOf(CustomException.class);
    }

    /** 입력 순서와 무관하게 동일한 귀속 순서를 만든다. */
    @Test
    void ordersTargetsByRegistrationId() {
        Registration b = registration("b", "40000", "30000",
                RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED);
        Registration a = registration("a", "40000", "30000",
                RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED);
        assertThat(resolver.resolve(List.of(b, a)))
                .extracting(target -> target.registration().getId())
                .containsExactly("a", "b");
    }

    /** 외부 조회 없이 금액 계산에 필요한 실제 엔티티를 구성한다. */
    private Registration registration(String id, String contract, String paid,
                                      RegistrationStatus status) {
        return Registration.builder().id(id)
                .contractAmount(new BigDecimal(contract))
                .paidAmount(new BigDecimal(paid)).status(status).build();
    }
}
