package kr.co.teambrain.marvelrun.admin.event.query.util;

import kr.co.teambrain.marvelrun.admin.event.query.dto.report.RegistrationDeliveryReportModels.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 단품 → 패키지 상향을 기다리는 신청은 단품 결제를 마쳤으므로 배송 명단에서 "추가결제 필요"로 보지 않는다. */
class RegistrationDeliveryClassifierPackageUpgradeTest {

    private static final LocalDateTime UTC = LocalDateTime.of(2026, 11, 1, 0, 0);

    private final RegistrationDeliveryClassifier classifier = new RegistrationDeliveryClassifier();

    /** 상향 대기면 계약금액(75,000)과 납부액(40,000)이 달라도 정상 행이다. */
    @Test
    void pendingUpgradeIsNotUnpaid() {
        Classification result = classify(candidate(true));

        assertThat(result.excluded()).isFalse();
        assertThat(result.reasons()).isEmpty();
    }

    /** 상향 대기가 아닌 추가 결제 필요 신청은 지금처럼 불명확 사유를 남긴다. */
    @Test
    void ordinaryAdditionalPaymentStaysUnclear() {
        Classification result = classify(candidate(false));

        assertThat(result.reasons()).contains("추가결제 필요");
    }

    private Classification classify(Candidate candidate) {
        PaymentFact paid = new PaymentFact("registration", "payment", "REGISTRATION_TRY", "COMPLETED", UTC,
                new BigDecimal("40000"), BigDecimal.ZERO, false, false, null);
        ReservationFact reservation = new ReservationFact("registration", "reservation", "CONSUMED", null,
                List.of(new CapacityItem("total", 1), new CapacityItem("category-cap", 1)));
        return classifier.classifyRegistrationForDelivery(candidate, List.of(paid), List.of(reservation),
                UTC.minusDays(1), UTC.plusDays(1));
    }

    private static Candidate candidate(boolean pendingUpgrade) {
        return new Candidate("registration", "ADDITIONAL_PAYMENT_REQUIRED", false, "테스트", "1990-01-01",
                "01012345678", "M", null, null, "category", "어벤저스", "[]", "주소", "상세", UTC.plusHours(9), UTC,
                new BigDecimal("75000"), new BigDecimal("40000"), pendingUpgrade);
    }
}
