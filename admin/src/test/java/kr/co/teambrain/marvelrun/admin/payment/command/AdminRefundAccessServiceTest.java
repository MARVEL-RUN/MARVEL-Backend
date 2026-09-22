package kr.co.teambrain.marvelrun.admin.payment.command;

import java.math.BigDecimal;
import java.util.List;
import java.util.Collections;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.admin.payment.command.AdminRefundLockedScope.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 관리자 진입 순서·대상 귀속·미확정 차단을 검증한다. 실제 DB 잠금 검증을 대신하지 않는다. */
class AdminRefundAccessServiceTest {
    private final AdminRefundLockRepository repository = mock(AdminRefundLockRepository.class);
    private final AdminRefundAccessService service = new AdminRefundAccessService(repository);

    /** 프록시 없는 단위 테스트에서 기존 쓰기 트랜잭션 문맥만 표시한다. */
    @BeforeEach
    void transaction() { TransactionSynchronizationManager.setActualTransactionActive(true); }
    /** 다른 테스트 스레드 사용에 트랜잭션 표시를 남기지 않는다. */
    @AfterEach
    void cleanup() { TransactionSynchronizationManager.clear(); }

    /** 단체 일부 요청도 단체 전체 결제 충돌을 먼저 검사한 후 선택 참가자를 잠근다. */
    @Test
    void locksGroupInUserOrderAndSortsTargets() {
        prepare("o", List.of("a", "b"));
        var result = service.lock("e", "o", List.of("b", "a"));
        var order = inOrder(repository);
        order.verify(repository).lockEvent("e");
        order.verify(repository).lockOrganization("e", "o");
        order.verify(repository).lockPayments("e", "o", "a");
        order.verify(repository).lockCancellations(List.of("p"));
        order.verify(repository).lockRegistrations("e", List.of("a", "b"));
        order.verifyNoMoreInteractions();
        assertThat(result.registrations()).extracting(RegistrationRow::id).containsExactly("a", "b");
        assertThat(result.registrations().getFirst().paidAmount()).isEqualByComparingTo("10000");
        assertThatThrownBy(() -> result.payments().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    /** 사용자와 동일한 결제 충돌 상태는 참가자 조회 전에 차단한다. */
    @ParameterizedTest
    @EnumSource(value = PaymentProcessStatus.class, names = {"CONFIRMING", "UNKNOWN"})
    void blocksUnresolvedPayment(PaymentProcessStatus status) {
        prepare(null, List.of("a"));
        when(repository.lockPayments("e", null, "a")).thenReturn(List.of(new PaymentRow("p", status)));
        expect(ErrorCode.REGISTRATION_MODIFICATION_PAYMENT_CONFLICT, null, List.of("a"));
        verify(repository, never()).lockCancellations(anyList());
        verify(repository, never()).lockRegistrations(anyString(), anyList());
    }

    /** 미확정 환불은 다른 구성원을 선택해도 같은 원결제 범위에서 차단한다. */
    @ParameterizedTest
    @EnumSource(value = PaymentCancelStatus.class, names = {"PROCESSING", "UNKNOWN"})
    void blocksUnresolvedRefund(PaymentCancelStatus status) {
        prepare("o", List.of("a"));
        when(repository.lockCancellations(List.of("p"))).thenReturn(List.of(new CancelRow("c", "p", status)));
        expect(ErrorCode.PAYMENT_CANCEL_CONFLICT, "o", List.of("a"));
        verify(repository, never()).lockRegistrations(anyString(), anyList());
    }

    /** 확정된 과거 환불 결과를 미확정으로 오인하지 않는다. 한도 대사는 다음 단계의 책임이다. */
    @ParameterizedTest
    @EnumSource(value = PaymentCancelStatus.class, names = {"DONE", "FAILED"})
    void retainsResolvedRefundHistory(PaymentCancelStatus status) {
        prepare(null, List.of("a"));
        when(repository.lockCancellations(List.of("p"))).thenReturn(List.of(new CancelRow("c", "p", status)));
        assertThat(service.lock("e", null, List.of("a")).cancellations()).hasSize(1);
    }

    /** 확정 상태라도 금액 요약이 깨졌으면 원장 준비로 진행하지 않는다. */
    @Test
    void rejectsInconsistentAmountsAndDeletedRegistration() {
        prepare(null, List.of("a"));
        for (RegistrationRow row : List.of(
                new RegistrationRow("a", null, false, RegistrationStatus.CONFIRMED, BigDecimal.ZERO, BigDecimal.ZERO),
                new RegistrationRow("a", null, false, RegistrationStatus.CONFIRMED, new BigDecimal("10000"), BigDecimal.ONE),
                new RegistrationRow("a", null, true, RegistrationStatus.CONFIRMED, BigDecimal.TEN, BigDecimal.TEN))) {
            when(repository.lockRegistrations("e", List.of("a"))).thenReturn(List.of(row));
            expect(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR, null, List.of("a"));
        }
    }

    /** 과거 실패·준비·무효 주문은 완료 원결제가 함께 있을 때 충돌 자체로 취급하지 않는다. */
    @ParameterizedTest
    @EnumSource(value = PaymentProcessStatus.class, names = {"READY", "FAILED", "INVALIDATED"})
    void retainsHistoryButRequiresCompletedPayment(PaymentProcessStatus history) {
        prepare(null, List.of("a"));
        when(repository.lockPayments("e", null, "a")).thenReturn(List.of(
                new PaymentRow("p", PaymentProcessStatus.COMPLETED), new PaymentRow("q", history)));
        assertThat(service.lock("e", null, List.of("a")).payments()).hasSize(2);
        when(repository.lockPayments("e", null, "a")).thenReturn(List.of(new PaymentRow("q", history)));
        expect(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR, null, List.of("a"));
    }

    /** 결제 완료 신청 외 상태는 관리자의 신규 환불 대상으로 확대하지 않는다. */
    @ParameterizedTest
    @EnumSource(value = RegistrationStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "CONFIRMED")
    void rejectsNonConfirmedRegistration(RegistrationStatus status) {
        prepare(null, List.of("a"));
        when(repository.lockRegistrations("e", List.of("a"))).thenReturn(List.of(
                new RegistrationRow("a", null, false, status, new BigDecimal("10000"), new BigDecimal("10000"))));
        expect(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR, null, List.of("a"));
    }

    /** 개인 경로로 단체원을 처리하거나 누락·중복 명단을 승인하지 않는다. */
    @Test
    void rejectsWrongOwnershipAndMissingTarget() {
        prepare(null, List.of("a"));
        when(repository.lockRegistrations("e", List.of("a"))).thenReturn(List.of(row("a", "other")));
        expect(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR, null, List.of("a"));
        when(repository.lockRegistrations("e", List.of("a"))).thenReturn(List.of());
        expect(ErrorCode.REGISTRATION_NOT_FOUND, null, List.of("a"));
    }

    /** 쓰기 트랜잭션이 없거나 입력이 과다하면 어떤 잠금도 취득하지 않는다. */
    @Test
    void rejectsInvalidEntryBeforeDatabaseAccess() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatThrownBy(() -> service.lock("e", null, List.of("a"))).isInstanceOf(IllegalStateException.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(() -> service.lock("e", null, List.of("a"))).isInstanceOf(IllegalStateException.class);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        assertThatThrownBy(() -> service.lock("e", "o", Collections.nCopies(101, "a"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.lock("e", "o", List.of("a", "a"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.lock("e", null, List.of("a", "b"))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    /** 조회 상한 초과는 일부 원장으로 진행하지 않고 트랜잭션 실패로 반환한다. */
    @Test
    void rejectsOverflowAndPropagatesLockFailure() {
        prepare(null, List.of("a"));
        when(repository.lockPayments("e", null, "a")).thenReturn(Collections.nCopies(501,
                new PaymentRow("p", PaymentProcessStatus.COMPLETED)));
        assertThatThrownBy(() -> service.lock("e", null, List.of("a"))).isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).lockRegistrations(anyString(), anyList());
        RuntimeException failure = new RuntimeException("database failure");
        when(repository.lockEvent("e")).thenThrow(failure);
        assertThatThrownBy(() -> service.lock("e", null, List.of("a"))).isSameAs(failure);
    }

    /** 잠금에 필요한 최소 원장 응답만 준비한다. */
    private void prepare(String organizationId, List<String> ids) {
        when(repository.lockEvent("e")).thenReturn(true);
        if (organizationId != null) { when(repository.lockOrganization("e", organizationId)).thenReturn(true); }
        when(repository.lockPayments("e", organizationId, ids.getFirst())).thenReturn(
                List.of(new PaymentRow("p", PaymentProcessStatus.COMPLETED)));
        when(repository.lockRegistrations("e", ids)).thenReturn(ids.stream().map(id -> row(id, organizationId)).toList());
    }
    /** 실제 원장 대사 이전의 정상 결제 완료 요약이다. */
    private RegistrationRow row(String id, String organizationId) {
        return new RegistrationRow(id, organizationId, false, RegistrationStatus.CONFIRMED,
                new BigDecimal("10000"), new BigDecimal("10000"));
    }
    /** 오류 식별자까지 검증하여 다른 실패가 성공으로 보이지 않게 한다. */
    private void expect(ErrorCode code, String organizationId, List<String> ids) {
        assertThatThrownBy(() -> service.lock("e", organizationId, ids)).isInstanceOfSatisfying(CustomException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
