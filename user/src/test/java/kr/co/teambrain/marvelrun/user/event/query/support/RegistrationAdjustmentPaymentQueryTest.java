package kr.co.teambrain.marvelrun.user.event.query.support;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import kr.co.teambrain.marvelrun.user.event.query.repository.RegistrationQueryData.*;
import kr.co.teambrain.marvelrun.user.event.query.dto.RegistrationPaymentAction;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
/* 조회 시 일반 추가 결제 안내와 외부 결제 신청의 온라인 결제 제한을 검증한다. */
class RegistrationAdjustmentPaymentQueryTest {
    private final RegistrationPaymentQueryResolver resolver = new RegistrationPaymentQueryResolver();
    private final LocalDateTime now = LocalDateTime.of(2026,11,2,12,0);
    @Test void missingOrderOffersAdditionalPreparationAfterDeadline() {
        var result = resolver.resolve(List.of(member(true)),List.of(),List.of(),List.of(),now.minusDays(1),now);
        assertThat(result.action()).isEqualTo(RegistrationPaymentAction.PREPARE_ADDITIONAL_PAYMENT);
        assertThat(result.paymentId()).isNull(); assertThat(result.orderId()).isNull();
    }
    @Test void initialPaymentStillClosesAtDeadline() {
        assertThat(resolver.resolve(List.of(member(false)),List.of(),List.of(),List.of(),now,now).action())
                .isEqualTo(RegistrationPaymentAction.PAYMENT_CLOSED);
    }
    @Test void matchingAdditionalReadyOrderUsesExistingPreparation() {
        Payment payment = new Payment("p","r","o",new BigDecimal("30000"),PaymentPurpose.ADDITIONAL_PAYMENT,PaymentProcessStatus.READY);
        var result = resolver.resolve(List.of(member(true)),List.of(payment),
                List.of(new Allocation("p","r",new BigDecimal("30000"),PaymentPurpose.ADDITIONAL_PAYMENT)),List.of(),now.minusDays(1),now);
        assertThat(result.action()).isEqualTo(RegistrationPaymentAction.PREPARE_PAYMENT);
        assertThat(result.paymentId()).isEqualTo("p");
    }
    @Test void unknownApprovalWinsOverAdditionalButton() {
        Payment payment = new Payment("p","r","o",new BigDecimal("30000"),PaymentPurpose.ADDITIONAL_PAYMENT,PaymentProcessStatus.UNKNOWN);
        assertThat(resolver.resolve(List.of(member(true)),List.of(payment),List.of(),List.of(),now.minusDays(1),now).action())
                .isEqualTo(RegistrationPaymentAction.WAIT);
    }
    /* 외부 신청에 미납 READY 주문이 남아 있더라도 온라인 결제 버튼을 안내하지 않는다. */
    @Test
    void externalPaymentNeverOffersOnlinePaymentPreparation() {
        // 미납 상태까지 구성하여 납부 완료 여부에 의존하지 않는 제한을 확인한다.
        Member external = new Member("r", "외부결제자", null, "1990-01-01", "010-0000-0000", "test",
                null, "c", "category", List.of(), null, null, null, false, null, null, null,
                RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED, new BigDecimal("70000"),
                new BigDecimal("40000"), false, ReservationStatus.CONSUMED, now.plusDays(1), true);
        Payment payment = new Payment("p", "r", "o", new BigDecimal("30000"),
                PaymentPurpose.ADDITIONAL_PAYMENT, PaymentProcessStatus.READY);

        // 외부 결제는 현재 금융 상태를 반환하되 실행 가능한 주문 ID를 제공하지 않는다.
        RegistrationPaymentQueryResolver.Result result = resolver.resolve(List.of(external), List.of(payment),
                List.of(new Allocation("p", "r", new BigDecimal("30000"), PaymentPurpose.ADDITIONAL_PAYMENT)),
                List.of(), now.plusDays(1), now);
        assertThat(result.action()).isEqualTo(RegistrationPaymentAction.NONE);
        assertThat(result.paymentId()).isNull();
        assertThat(result.status()).isEqualTo(PaymentProcessStatus.READY);
    }

    private Member member(boolean additional) {
        return new Member("r","name",null,"1990-01-01","010-0000-0000","test",null,"c","category",List.of(),
                null,null,null,false,null,null,null,additional ? RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED : RegistrationStatus.PAYMENT_PENDING,
                new BigDecimal("70000"),additional ? new BigDecimal("40000") : BigDecimal.ZERO,false,
                additional ? ReservationStatus.CONSUMED : ReservationStatus.HELD,now.minusDays(1), false);
    }
}
