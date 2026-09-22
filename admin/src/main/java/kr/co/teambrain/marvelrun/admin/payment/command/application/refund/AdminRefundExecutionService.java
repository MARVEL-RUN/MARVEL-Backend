package kr.co.teambrain.marvelrun.admin.payment.command.application.refund;
import java.time.LocalDateTime;
import java.util.List;
import kr.co.teambrain.marvelrun.admin.payment.command.AdminRefundPrepared;
import kr.co.teambrain.marvelrun.admin.payment.command.AdminRefundTime;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
/** 관리자 준비 커밋 결과를 실행기에 연결한다. 외부 트랜잭션 안에서는 실행기가 차단한다. */
@Service
@RequiredArgsConstructor
public class AdminRefundExecutionService {
    private final ModificationRefundExecutor executor;
    private final AdminRefundTime time;
    /** 클라이언트 금액을 받지 않고 준비 서비스가 반환한 원결제별 시도만 처리한다. */
    public Result execute(AdminRefundPrepared prepared) {
        if (prepared == null) { throw new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR); }
        List<AdminRefundExecutionRequest.Refund> requests = prepared.refunds().stream()
                .map(r -> new AdminRefundExecutionRequest.Refund(r.paymentCancelId(), r.paymentId(), r.amount(), r.status(), prepared.correlationId())).toList();
        List<AdminRefundExecutionResult> results = executor.execute(prepared.eventId(), prepared.organizationId(), requests);
        return new Result(prepared.requestId(), prepared.correlationId(), prepared.preparedAt(), time.now(), results);
    }
    /** finishedAt은 실행기 종료 시각이며 개별 PG 완료 시각은 externalOutcome의 증거를 사용한다. */
    public record Result(String requestId, String correlationId, LocalDateTime preparedAt,
            LocalDateTime finishedAt, List<AdminRefundExecutionResult> refunds) {
        /** 반환 목록을 불변으로 보존한다. */
        public Result { refunds = List.copyOf(refunds); }
    }
}
