package kr.co.teambrain.marvelrun.user.event.command.application.domain;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 상향 요청·취소·적용이 신청의 계약금액·상태·종목을 기존 추가 결제 규칙에 맞게 바꾸는지 본다. */
class RegistrationPackageUpgradeTest {

    private static final BigDecimal SINGLE = new BigDecimal("40000");
    private static final BigDecimal DIFF = new BigDecimal("35000");

    /** 요청하면 계약금액에 차액이 더해지고 추가 결제 대기가 된다. 종목은 그대로다. */
    @Test
    void startMovesConfirmedToAdditionalPaymentRequired() {
        EventCategory single = EventCategory.builder().id("single").build();
        Registration registration = confirmed(single);

        registration.startPackageUpgrade(DIFF);

        assertThat(registration.getContractAmount()).isEqualByComparingTo("75000");
        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED);
        assertThat(registration.getEventCategory()).isSameAs(single);
    }

    /** 결제를 마치지 않았거나 단체 신청이면 상향하지 않는다. */
    @Test
    void startRejectsUnpaidOrOrganization() {
        Registration unpaid = Registration.builder().status(RegistrationStatus.PAYMENT_PENDING)
                .contractAmount(SINGLE).paidAmount(BigDecimal.ZERO).build();
        Registration organization = Registration.builder().status(RegistrationStatus.CONFIRMED)
                .contractAmount(SINGLE).paidAmount(SINGLE).organization(Organization.builder().id("org").build()).build();

        expect(ErrorCode.PACKAGE_UPGRADE_NOT_ALLOWED, () -> unpaid.startPackageUpgrade(DIFF));
        expect(ErrorCode.PACKAGE_UPGRADE_NOT_ALLOWED, () -> organization.startPackageUpgrade(DIFF));
        expect(ErrorCode.PACKAGE_UPGRADE_NOT_ALLOWED, () -> confirmed(null).startPackageUpgrade(BigDecimal.ZERO));
    }

    /** 취소하면 요청 전 계약금액과 확정 상태로 돌아간다. 차액이 맞지 않으면 바꾸지 않는다. */
    @Test
    void cancelRestoresConfirmed() {
        Registration registration = confirmed(null);
        registration.startPackageUpgrade(DIFF);

        expect(ErrorCode.PACKAGE_UPGRADE_STATE_CONFLICT, () -> registration.cancelPackageUpgrade(new BigDecimal("1000")));
        registration.cancelPackageUpgrade(DIFF);

        assertThat(registration.getContractAmount()).isEqualByComparingTo(SINGLE);
        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.CONFIRMED);
    }

    /** 승인 때 종목·기념품을 바꾸고 차액이 납부되면 기존 규칙대로 확정된다. */
    @Test
    void applyThenPaymentConfirms() {
        EventCategory pkg = EventCategory.builder().id("package").build();
        Registration registration = confirmed(EventCategory.builder().id("single").build());
        registration.startPackageUpgrade(DIFF);
        List<SouvenirJson> kits = List.of(new SouvenirJson("kit-a", "FREE"), new SouvenirJson("kit-b", "FREE"));

        registration.applyPackageUpgrade(pkg, kits);
        registration.applySuccessfulPayment(DIFF);

        assertThat(registration.getEventCategory()).isSameAs(pkg);
        assertThat(registration.getSouvenirJson()).isEqualTo(kits);
        assertThat(registration.getPaidAmount()).isEqualByComparingTo("75000");
        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.CONFIRMED);
        assertThat(registration.reconcileModificationFinancialState(ReservationStatus.CONSUMED)).isZero();
    }

    private static Registration confirmed(EventCategory category) {
        return Registration.builder().id("registration").eventCategory(category)
                .status(RegistrationStatus.CONFIRMED).contractAmount(SINGLE).paidAmount(SINGLE).build();
    }

    private static void expect(ErrorCode errorCode, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(errorCode);
    }
}
