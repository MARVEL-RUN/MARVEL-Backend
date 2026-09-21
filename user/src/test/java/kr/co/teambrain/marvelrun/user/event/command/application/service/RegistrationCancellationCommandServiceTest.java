package kr.co.teambrain.marvelrun.user.event.command.application.service;

import java.math.BigDecimal;
import java.util.List;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult.Refund;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.payment.command.application.refund.ModificationRefundExecutor;
import kr.co.teambrain.marvelrun.user.payment.command.application.refund.ModificationRefundResultReader;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 취소 트랜잭션 종료 이후의 환불 실행 순서와 중복 재전송 방지를 검증한다. */
class RegistrationCancellationCommandServiceTest {
    private final RegistrationCancellationTransactionService transactions = mock(RegistrationCancellationTransactionService.class);
    private final ModificationRefundExecutor executor = mock(ModificationRefundExecutor.class);
    private final ModificationRefundResultReader reader = mock(ModificationRefundResultReader.class);
    private final RegistrationCancellationCommandService service = new RegistrationCancellationCommandService(transactions, executor, reader);
    private final RegistrationAccessRequest access = new RegistrationAccessRequest("참가자", "1990-01-01", "010-0000-0000", "Test1234!");

    /** 준비 서비스가 반환한 뒤 외부 실행과 최종 결과 조회를 순서대로 수행한다. */
    @Test
    void executesRefundOnlyAfterPreparationReturns() {
        RegistrationModificationSettlementResult prepared = result();
        when(transactions.cancelPersonal("event", "registration", access))
                .thenReturn(new RegistrationCancellationTransactionService.Prepared(prepared, true));
        when(reader.read(prepared)).thenReturn(prepared);
        assertThat(service.cancelPersonal("event", "registration", access)).isSameAs(prepared);
        InOrder order = inOrder(transactions, executor, reader);
        order.verify(transactions).cancelPersonal("event", "registration", access);
        order.verify(executor).execute("event", null, prepared.refunds());
        order.verify(reader).read(prepared);
    }

    /** 이미 취소한 신청은 미완료 환불이 있어도 새 외부 호출을 자동으로 만들지 않는다. */
    @Test
    void repeatedCancellationReadsResultWithoutExecutingRefund() {
        RegistrationModificationSettlementResult prepared = result();
        when(transactions.cancelPersonal("event", "registration", access))
                .thenReturn(new RegistrationCancellationTransactionService.Prepared(prepared, false));
        when(reader.read(prepared)).thenReturn(prepared);
        assertThat(service.cancelPersonal("event", "registration", access)).isSameAs(prepared);
        verifyNoInteractions(executor);
        verify(reader).read(prepared);
    }

    /** 미납·무료 취소는 외부 환불 실행과 결과 재조회를 생략한다. */
    @Test
    void noRefundSkipsFinancialExecutionAndRead() {
        RegistrationModificationSettlementResult prepared = new RegistrationModificationSettlementResult(List.of(), List.of(), List.of());
        when(transactions.cancelPersonal("event", "registration", access))
                .thenReturn(new RegistrationCancellationTransactionService.Prepared(prepared, true));
        assertThat(service.cancelPersonal("event", "registration", access)).isSameAs(prepared);
        verifyNoInteractions(executor, reader);
    }

    /** 호출 순서 검증에 필요한 불변 환불 응답을 구성한다. */
    private RegistrationModificationSettlementResult result() {
        return new RegistrationModificationSettlementResult(List.of(), List.of(),
                List.of(new Refund("cancel", "payment", new BigDecimal("40000"), PaymentCancelStatus.PROCESSING, "correlation")));
    }
}
