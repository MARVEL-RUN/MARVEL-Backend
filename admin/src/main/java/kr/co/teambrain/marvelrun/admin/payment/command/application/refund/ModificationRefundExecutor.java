package kr.co.teambrain.marvelrun.admin.payment.command.application.refund;

import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import kr.co.teambrain.marvelrun.admin.payment.command.application.refund.AdminRefundExecutionRequest.Refund;
import kr.co.teambrain.marvelrun.admin.payment.command.infrastructure.toss.refund.*;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 커밋된 환불 시도를 외부 호출과 결과 저장으로 연결하며 자동 재전송하지 않는다. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModificationRefundExecutor {
    private final RefundExecutionTransactionService transactions;
    private final TossPaymentCancelClient toss;

    /** 각 원결제를 독립 처리하여 일부 성공 후 다른 건 실패가 이미 완료한 환불을 되돌리지 않는다. */
    public List<AdminRefundExecutionResult> execute(String eventId, String organizationId, List<Refund> refunds) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new CustomException(ErrorCode.PAYMENT_CANCEL_CONFLICT);
        }
        if (refunds == null || refunds.isEmpty() || refunds.size() > 500
                || refunds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
        }
        List<AdminRefundExecutionResult> results = new ArrayList<>();
        for (Refund refund : refunds) {
            Optional<RefundExecutionTicket> claimed;
            try {
                claimed = transactions.begin(eventId, organizationId, refund);
            } catch (RuntimeException failure) {
                // 시작 트랜잭션이 커밋됐는지 불확실한 경우에도 외부 호출하지 않는다.
                log.error("환불 시작 기록 확인 실패. paymentCancelId={}, errorType={}",
                        refund.paymentCancelId(), failure.getClass().getSimpleName());
                results.add(new AdminRefundExecutionResult(refund.paymentCancelId(), false, null, false, false, "CANCEL_BEGIN_FAILED"));
                continue;
            }
            if (claimed.isEmpty()) {
                results.add(new AdminRefundExecutionResult(refund.paymentCancelId(), false, null, false, false, "CANCEL_NOT_CLAIMED"));
                continue;
            }
            RefundExecutionTicket ticket = claimed.get();
            TossCancelOutcome outcome;
            try {
                outcome = toss.cancel(ticket.attempt());
                if (outcome == null) { outcome = TossCancelOutcome.unknown(null, "EMPTY_CANCEL_OUTCOME"); }
            } catch (RuntimeException failure) {
                outcome = TossCancelOutcome.unknown(null, "UNEXPECTED_CANCEL_CALL_FAILURE");
            }
            try {
                transactions.apply(ticket, outcome);
                results.add(new AdminRefundExecutionResult(refund.paymentCancelId(), true, outcome, true, false, null));
            } catch (RuntimeException failure) {
                log.error("환불 결과 저장 실패. paymentCancelId={}, errorType={}",
                        refund.paymentCancelId(), failure.getClass().getSimpleName());
                // 외부 성공 증거는 UNKNOWN 로그에도 전달한다. 순결제금액은 결과 트랜잭션 롤백으로 보존된다.
                TossCancelOutcome unknown = new TossCancelOutcome(TossCancelOutcome.Kind.UNKNOWN,
                        outcome.cancellation(), outcome.httpStatus(), "CANCEL_RESULT_SAVE_FAILED",
                        "외부 취소 결과를 DB에 반영하지 못했습니다. 확인 전 재요청할 수 없습니다.");
                try {
                    transactions.apply(ticket, unknown);
                    results.add(new AdminRefundExecutionResult(refund.paymentCancelId(), true, outcome, false, true, "CANCEL_RESULT_SAVE_FAILED"));
                } catch (RuntimeException unknownFailure) {
                    // DB 자체 장애면 PROCESSING이 남아도 후속 금융 변경을 막는다. 성공으로 응답하지 않는다.
                    log.error("환불 결과불명 저장 실패. paymentCancelId={}, errorType={}",
                            refund.paymentCancelId(), unknownFailure.getClass().getSimpleName());
                    results.add(new AdminRefundExecutionResult(refund.paymentCancelId(), true, outcome, false, false, "CANCEL_UNKNOWN_SAVE_FAILED"));
                }
            }
        }
        return List.copyOf(results);
    }
}