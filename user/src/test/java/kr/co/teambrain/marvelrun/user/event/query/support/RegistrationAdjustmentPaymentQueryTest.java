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
    private Member member(boolean additional) {
        return new Member("r","name",null,"1990-01-01","010-0000-0000","test",null,"c","category",List.of(),
                null,null,null,false,null,null,null,additional ? RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED : RegistrationStatus.PAYMENT_PENDING,
                new BigDecimal("70000"),additional ? new BigDecimal("40000") : BigDecimal.ZERO,false,
                additional ? ReservationStatus.CONSUMED : ReservationStatus.HELD,now.minusDays(1));
    }
}
