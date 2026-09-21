package kr.co.teambrain.marvelrun.user.event.command.application.service;

import java.math.BigDecimal;
import java.util.List;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult.Refund;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.payment.command.application.refund.ModificationRefundExecutor;
import kr.co.teambrain.marvelrun.user.payment.command.application.refund.ModificationRefundResultReader;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 기존 수정 API가 준비 커밋 이후 환불 실행과 최종 응답으로 이어지는지 검증한다. */
class RegistrationModificationRefundFacadeTest {
    private final RegistrationModificationTransactionService transactions = mock(RegistrationModificationTransactionService.class);
    private final ModificationRefundExecutor refunds = mock(ModificationRefundExecutor.class);
    private final ModificationRefundResultReader results = mock(ModificationRefundResultReader.class);
    private final RegistrationModificationCommandService commands = new RegistrationModificationCommandService(transactions, refunds, results);

    /** 환불이 없으면 결과 조회와 외부 처리 자체를 생략한다. */
    @Test
    void noRefundSkipsAllAdditionalWork() {
        RegistrationModificationRequest request = mock(RegistrationModificationRequest.class);
        RegistrationModificationSettlementResult prepared = new RegistrationModificationSettlementResult(List.of(), List.of());
        when(transactions.modifyPersonal("event", "registration", request)).thenReturn(prepared);
        assertThat(commands.modifyPersonal("event", "registration", request)).isSameAs(prepared);
        verifyNoInteractions(refunds, results);
    }

    /** 단체 수정의 준비 결과를 실행한 후 저장된 결과로 응답한다. */
    @Test
    void runsRefundAfterPreparationAndReadsStoredResult() {
        OrgRegistrationModificationRequest request = mock(OrgRegistrationModificationRequest.class);
        RegistrationModificationSettlementResult prepared = new RegistrationModificationSettlementResult(List.of(), List.of(),
                List.of(new Refund("cancel", "payment", new BigDecimal("10000"), PaymentCancelStatus.PROCESSING, "correlation")));
        RegistrationModificationSettlementResult finished = new RegistrationModificationSettlementResult(List.of(), List.of(),
                List.of(new Refund("cancel", "payment", new BigDecimal("10000"), PaymentCancelStatus.DONE, "correlation")));
        when(transactions.modifyOrganization("event", "org", request)).thenReturn(prepared);
        when(results.read(prepared)).thenReturn(finished);
        assertThat(commands.modifyOrganization("event", "org", request)).isSameAs(finished);
        InOrder order = inOrder(transactions, refunds, results);
        order.verify(transactions).modifyOrganization("event", "org", request);
        order.verify(refunds).execute("event", "org", prepared.refunds());
        order.verify(results).read(prepared);
    }
}
