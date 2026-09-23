package kr.co.teambrain.marvelrun.admin.payment.command.application;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelType;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.user.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.PaymentCancel;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.payment.command.application.creator.ModificationRefundPlanner;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.admin.payment.command.application.domain.PaymentAllocation;
import kr.co.teambrain.marvelrun.admin.payment.command.application.domain.PaymentCancelAllocation;
import kr.co.teambrain.marvelrun.admin.payment.command.application.dto.RefundPaymentLedger;
import kr.co.teambrain.marvelrun.admin.payment.command.application.dto.RefundPreparationPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** 환불을 원 결제·구성원 귀속 한도 안에서만 배분하는 순수 계산 계약을 검증한다. */
class ModificationRefundPlannerTest {
    private final ModificationRefundPlanner planner = new ModificationRefundPlanner();

    /** 한 신청의 여러 승인 건을 고정 순서로 나누어 환불한다. */
    @Test
    void splitsRefundAcrossOriginalPayments() {
        Registration r = registration("r", null, "10000", "50000", false);
        Payment a = payment("application-admin-refund-test.yml", r, null, "30000");
        Payment b = payment("b", r, null, "20000");
        PaymentAllocation aa = allocation("aa", a, r, "30000");
        PaymentAllocation ab = allocation("ab", b, r, "20000");
        List<RefundPreparationPlan> plans = planner.plan(List.of(r), List.of(ledger(b, ab), ledger(a, aa)));
        assertThat(plans).hasSize(2);
        assertThat(plans.get(0).payment()).isSameAs(a);
        assertThat(plans.get(0).amount()).isEqualByComparingTo("30000");
        assertThat(plans.get(0).type()).isEqualTo(PaymentCancelType.FULL);
        assertThat(plans.get(1).amount()).isEqualByComparingTo("10000");
        assertThat(plans.get(1).type()).isEqualTo(PaymentCancelType.PARTIAL);
        assertThat(r.getPaidAmount()).isEqualByComparingTo("50000");
    }

    /** 과거 성공 환불을 차감한 원 귀속 잔액으로 계획한다. */
    @Test
    void subtractsCompletedRefundBeforePreparingAnother() {
        Registration r = registration("r", null, "20000", "40000", false);
        Payment p = payment("p", r, null, "50000");
        PaymentAllocation a = allocation("application-admin-refund-test.yml", p, r, "50000");
        PaymentCancel c = cancellation(p, PaymentCancelStatus.DONE, "10000");
        RefundPaymentLedger ledger = new RefundPaymentLedger(p, List.of(a), List.of(c), List.of(cancelAllocation(c, a, "10000")));
        List<RefundPreparationPlan> plans = planner.plan(List.of(r), List.of(ledger));
        assertThat(plans.get(0).amount()).isEqualByComparingTo("20000");
        assertThat(plans.get(0).type()).isEqualTo(PaymentCancelType.PARTIAL);
    }

    /** 명확 실패한 과거 시도는 한도를 소모하지 않는다. */
    @Test
    void failedRefundDoesNotConsumeBudget() {
        Registration r = registration("r", null, "30000", "40000", false);
        Payment p = payment("p", r, null, "40000");
        PaymentAllocation a = allocation("application-admin-refund-test.yml", p, r, "40000");
        PaymentCancel c = cancellation(p, PaymentCancelStatus.FAILED, "10000");
        RefundPaymentLedger ledger = new RefundPaymentLedger(p, List.of(a), List.of(c), List.of(cancelAllocation(c, a, "10000")));
        assertThat(planner.plan(List.of(r), List.of(ledger)).get(0).amount()).isEqualByComparingTo("10000");
    }

    /** 진행 중·결과불명 환불이 있으면 신규 시도를 만들 수 없다. */
    @ParameterizedTest
    @EnumSource(value = PaymentCancelStatus.class, names = {"PROCESSING", "UNKNOWN"})
    void unsettledRefundBlocksPreparation(PaymentCancelStatus status) {
        Registration r = registration("r", null, "30000", "40000", false);
        Payment p = payment("p", r, null, "40000");
        PaymentAllocation a = allocation("application-admin-refund-test.yml", p, r, "40000");
        PaymentCancel c = cancellation(p, status, "5000");
        RefundPaymentLedger ledger = new RefundPaymentLedger(p, List.of(a), List.of(c), List.of(cancelAllocation(c, a, "5000")));
        assertError(() -> planner.plan(List.of(r), List.of(ledger)), ErrorCode.PAYMENT_CANCEL_CONFLICT);
    }

    /** 단체 제거 구성원 환불과 남은 구성원 차액을 같은 원 결제에 정확히 귀속한다. */
    @Test
    void removedAndRetainedMembersKeepSeparateRefundAllocations() {
        Organization org = organization("org");
        Registration a = registration("application-admin-refund-test.yml", org, "30000", "40000", false);
        Registration b = registration("b", org, "0", "40000", true);
        Payment p = payment("p", null, org, "80000");
        PaymentAllocation aa = allocation("aa", p, a, "40000");
        PaymentAllocation ab = allocation("ab", p, b, "40000");
        RefundPreparationPlan plan = planner.plan(List.of(a, b), List.of(
                new RefundPaymentLedger(p, List.of(aa, ab), List.of(), List.of()))).get(0);
        assertThat(plan.amount()).isEqualByComparingTo("50000");
        assertThat(plan.type()).isEqualTo(PaymentCancelType.PARTIAL);
        assertThat(plan.targets()).hasSize(2);
        assertThat(plan.targets().get(0).amount()).isEqualByComparingTo("10000");
        assertThat(plan.targets().get(1).amount()).isEqualByComparingTo("40000");
        assertThat(b.isSoftDeleted()).isTrue();
        assertThat(b.getPaidAmount()).isEqualByComparingTo("40000");
    }

    /** 다른 구성원의 귀속 금액을 가져와 신청의 잘못된 순납부액을 채우지 않는다. */
    @Test
    void cannotBorrowAnotherMembersAllocation() {
        Organization org = organization("org");
        Registration a = registration("application-admin-refund-test.yml", org, "0", "50000", true);
        Registration b = registration("b", org, "40000", "40000", false);
        Payment p = payment("p", null, org, "80000");
        RefundPaymentLedger ledger = new RefundPaymentLedger(p, List.of(
                allocation("aa", p, a, "40000"), allocation("ab", p, b, "40000")), List.of(), List.of());
        assertError(() -> planner.plan(List.of(a), List.of(ledger)), ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
    }

    /** 부모 취소금액과 취소 귀속 합계가 다르면 저장 전 거절한다. */
    @Test
    void rejectsCancelAllocationSumMismatch() {
        Registration r = registration("r", null, "20000", "30000", false);
        Payment p = payment("p", r, null, "40000");
        PaymentAllocation a = allocation("application-admin-refund-test.yml", p, r, "40000");
        PaymentCancel c = cancellation(p, PaymentCancelStatus.DONE, "10000");
        RefundPaymentLedger ledger = new RefundPaymentLedger(p, List.of(a), List.of(c), List.of(cancelAllocation(c, a, "9000")));
        assertError(() -> planner.plan(List.of(r), List.of(ledger)), ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
    }

    /** 부모 한도가 남아도 특정 구성원의 누적 환불이 원 귀속을 초과하면 거절한다. */
    @Test
    void rejectsAllocationOverRefundEvenWhenPaymentBudgetRemains() {
        Organization org = organization("org");
        Registration a = registration("application-admin-refund-test.yml", org, "0", "10000", true);
        Registration b = registration("b", org, "30000", "30000", false);
        Payment p = payment("p", null, org, "40000");
        PaymentAllocation aa = allocation("aa", p, a, "10000");
        PaymentAllocation ab = allocation("ab", p, b, "30000");
        PaymentCancel c = cancellation(p, PaymentCancelStatus.DONE, "15000");
        RefundPaymentLedger ledger = new RefundPaymentLedger(p, List.of(aa, ab), List.of(c), List.of(cancelAllocation(c, aa, "15000")));
        assertThatThrownBy(() -> planner.plan(List.of(a), List.of(ledger))).isInstanceOf(CustomException.class);
    }

    /** 초과 납부가 없으면 0원 환불을 생성하지 않는다. */
    @Test
    void noExcessStillReconcilesPaidLedger() {
        Registration r = registration("r", null, "40000", "40000", false);
        Payment p = payment("p", r, null, "40000");
        assertThat(planner.plan(List.of(r), List.of(ledger(p, allocation("a",p,r,"40000"))))).isEmpty();
        assertError(() -> planner.plan(List.of(r), List.of()), ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
    }

    /** 신청에 순납부액만 있고 이를 설명할 원장이 없다면 임의 환불하지 않는다. */
    @Test
    void missingLedgerIsRejected() {
        Registration r = registration("r", null, "30000", "40000", false);
        assertError(() -> planner.plan(List.of(r), List.of()), ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
    }

    /** 수정 후 정산된 참가자 금액 상태를 구성한다. */
    private Registration registration(String id, Organization org, String contract, String paid, boolean deleted) {
        BigDecimal balance = new BigDecimal(contract).subtract(new BigDecimal(paid));
        RegistrationStatus status = deleted ? RegistrationStatus.CANCELLATION_PENDING
                : balance.signum() < 0 ? RegistrationStatus.PARTIAL_REFUND_REQUIRED : RegistrationStatus.CONFIRMED;
        return Registration.builder().id(id).organization(org).contractAmount(new BigDecimal(contract))
                .paidAmount(new BigDecimal(paid)).softDeleted(deleted).status(status).build();
    }

    /** 승인 완료된 원 결제를 구성한다. */
    private Payment payment(String id, Registration r, Organization org, String amount) {
        return Payment.builder().id(id).registration(r).organization(org).amount(new BigDecimal(amount))
                .paymentKey("test-key-" + id).processStatus(PaymentProcessStatus.COMPLETED).build();
    }

    /** 원 결제의 참가자별 귀속을 구성한다. */
    private PaymentAllocation allocation(String id, Payment p, Registration r, String amount) {
        return PaymentAllocation.builder().id(id).payment(p).registration(r).allocatedAmount(new BigDecimal(amount)).build();
    }

    /** 과거 취소가 없는 원장을 구성한다. */
    private RefundPaymentLedger ledger(Payment p, PaymentAllocation a) {
        return new RefundPaymentLedger(p, List.of(a), List.of(), List.of());
    }

    /** 지정 상태의 과거 취소를 구성한다. */
    private PaymentCancel cancellation(Payment p, PaymentCancelStatus status, String amount) {
        return PaymentCancel.builder().id("cancel").payment(p).status(status).cancelAmount(new BigDecimal(amount)).build();
    }

    /** 과거 취소와 원 귀속의 연결을 구성한다. */
    private PaymentCancelAllocation cancelAllocation(PaymentCancel c, PaymentAllocation a, String amount) {
        return PaymentCancelAllocation.builder().id("ca-" + a.getId()).paymentCancel(c)
                .originalAllocation(a).allocatedAmount(new BigDecimal(amount)).build();
    }

    /** 오류 종류뿐 아니라 업무 오류 코드도 확인한다. */
    private void assertError(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CustomException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
    /** 관리자 기존 Organization Entity에 테스트 전용 생성 API를 추가하지 않고 식별자만 구성한다. */
    private Organization organization(String id) {
        Organization organization = org.mockito.Mockito.mock(Organization.class);
        org.mockito.Mockito.when(organization.getId()).thenReturn(id);
        return organization;
    }
}