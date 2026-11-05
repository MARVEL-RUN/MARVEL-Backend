package kr.co.teambrain.marvelrun.user.event.query.support;

import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.user.event.query.dto.RegistrationPaymentAction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 상향을 기다리는 신청은 결제를 마친 단품처럼 안내하고, 결제 결과 확인 중이면 그 안내를 먼저 낸다. */
class RegistrationPaymentQueryResolverPackageUpgradeTest {

    private final RegistrationPaymentQueryResolver resolver = new RegistrationPaymentQueryResolver();

    /** 일반 추가 결제 안내(주문 id 포함)를 지우고 상향 화면으로 안내한다. 최근 결제 상태와 주문번호는 그대로 둔다. */
    @Test
    void replacesAdditionalPaymentGuidance() {
        RegistrationPaymentQueryResolver.Result base = new RegistrationPaymentQueryResolver.Result(
                PaymentProcessStatus.READY, null, RegistrationPaymentAction.PREPARE_PAYMENT,
                "확정 신청의 추가 납부가 필요합니다.", "payment-upgrade", "order-upgrade");

        RegistrationPaymentQueryResolver.Result result = resolver.resolvePendingPackageUpgradeGuidance(base);

        assertThat(result.action()).isEqualTo(RegistrationPaymentAction.NONE);
        assertThat(result.paymentId()).isNull();
        assertThat(result.warning()).contains("상향 화면");
        assertThat(result.status()).isEqualTo(PaymentProcessStatus.READY);
        assertThat(result.orderId()).isEqualTo("order-upgrade");
    }

    /** 승인 결과를 모르는 상향 결제가 있으면 새 결제를 막는 WAIT 안내를 그대로 낸다. */
    @Test
    void keepsWaitGuidance() {
        RegistrationPaymentQueryResolver.Result base = new RegistrationPaymentQueryResolver.Result(
                PaymentProcessStatus.UNKNOWN, null, RegistrationPaymentAction.WAIT,
                "결제 결과를 확인 중입니다. 새 결제를 시도하지 마세요.", null, "order-upgrade");

        assertThat(resolver.resolvePendingPackageUpgradeGuidance(base)).isSameAs(base);
    }
}
