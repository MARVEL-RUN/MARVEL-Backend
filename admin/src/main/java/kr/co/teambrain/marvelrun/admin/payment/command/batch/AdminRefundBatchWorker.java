package kr.co.teambrain.marvelrun.admin.payment.command.batch;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.payment.command.*;
import kr.co.teambrain.marvelrun.admin.payment.command.application.refund.*;
import kr.co.teambrain.marvelrun.admin.payment.command.infrastructure.toss.refund.TossCancelOutcome;
import kr.co.teambrain.marvelrun.admin.payment.command.batch.AdminRefundBatchModels.*;

/** 한 번에 참가자 한 명만 실행하며 배치 예외를 수집한 후 다음 대상이 진행되게 한다. */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminRefundBatchWorker {
    private final AdminRefundBatchStore store;
    private final AdminRefundPreparationService preparation;
    private final AdminRefundExecutionService execution;

    /** 테스트도 해당 배치만 지정하므로 다른 대기 작업을 실행하지 않는다. */
    public boolean processOne(String batchId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) { throw new IllegalStateException("배치 실행은 외부 트랜잭션 없이 호출해야 합니다."); }
        Work work = store.claim(batchId);
        if (work == null) { return false; }
        AdminRefundPrepared prepared = null;
        try {
            Target target = work.target();
            if (target.version() == null) { throw new IllegalStateException("접수 버전이 없습니다."); }
            AdminRefundCommandContext context = new AdminRefundCommandContext(work.requestId(),work.adminId(),work.reason(),target.version(),work.batchId(),work.itemNo());
            prepared = work.operation() == Operation.FULL
                    ? preparation.prepareFull(work.eventId(),target.organizationId(),List.of(target.registrationId()),context)
                    : preparation.preparePartial(work.eventId(),target.organizationId(),List.of(target.change()),context);
            // 정보 변경은 준비 트랜잭션 안에서 이미 원자적으로 저장했다.
            if (work.operation() == Operation.FULL) { store.prepared(work,prepared); }
        } catch (RuntimeException error) {
            String code = error instanceof CustomException custom ? custom.getErrorCode().name() : "PREPARATION_OR_JOURNAL_ERROR";
            // 준비가 커밋되었거나 커밋 결과가 모호한 경우 환불을 새로 시작하지 않는다.
            String status = prepared == null && error instanceof CustomException ? "BLOCKED" : "NEEDS_REVIEW";
            Map<String,Object> detail = new java.util.LinkedHashMap<>();
            detail.put("message", error instanceof CustomException ? error.getMessage()
                    : "처리 결과를 확정하지 못했습니다. 같은 요청 ID로 조회하고 확인해 주세요.");
            if (prepared != null) { detail.put("preparation", prepared); }
            return finish(work,status,code,detail);
        }
        if (prepared.refunds().isEmpty() && work.operation() == Operation.PARTIAL) {
            // 업무 변경은 이미 커밋되었다. 환불이 없다고 실패 처리하거나 PG 실행기를 호출하지 않는다.
            boolean additional = prepared.members().stream().anyMatch(AdminRefundPrepared.Member::additionalPaymentRequired);
            return finish(work, "SUCCEEDED", null, Map.of("members", prepared.members(), "refunds", List.of(),
                    "orders", List.of(), "message", additional
                            ? "신청 정보 수정 완료. 추가 납부가 필요합니다. 대상자에게 금액과 변경 사유를 안내해 주세요."
                            : "신청 정보 수정 완료. 추가 결제 또는 환불이 없습니다."));
        }
        AdminRefundExecutionService.Result result;
        try {
            result = execution.execute(prepared);
        } catch (RuntimeException error) {
            return finish(work,"NEEDS_REVIEW","EXECUTION_INTERRUPTED",null);
        }
        List<AdminRefundExecutionResult> refunds = result.refunds();
        boolean success = !refunds.isEmpty() && refunds.size() == prepared.refunds().size()
                && refunds.stream().allMatch(r -> r.started() && r.outcomeStored() && r.externalOutcome() != null
                && r.externalOutcome().kind() == TossCancelOutcome.Kind.VERIFIED);
        boolean rejected = !refunds.isEmpty() && refunds.size() == prepared.refunds().size()
                && refunds.stream().allMatch(r -> r.started() && r.outcomeStored() && r.externalOutcome() != null
                && r.externalOutcome().kind() == TossCancelOutcome.Kind.REJECTED);
        return finish(work,success ? "SUCCEEDED" : rejected ? "FAILED" : "NEEDS_REVIEW",success ? null : "REFUND_RESULT_REQUIRES_CHECK",result);
    }
    /** 최종 저장 실패 시 RUNNING 소유권을 유지해 재전송을 방지한다. PG 결과의 저장을 보장했다고 주장하지 않는다. */
    private boolean finish(Work work,String status,String errorCode,Object result) {
        try { store.finish(work,status,errorCode,result); return true; }
        catch (RuntimeException error) {
            log.error("환불 배치 결과 저장 실패: batchId={}, itemNo={}, 자동 재실행 금지",work.batchId(),work.itemNo(),error);
            return false;
        }
    }
}
