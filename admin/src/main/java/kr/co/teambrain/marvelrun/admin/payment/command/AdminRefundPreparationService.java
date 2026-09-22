package kr.co.teambrain.marvelrun.admin.payment.command;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import kr.co.teambrain.marvelrun.admin.payment.command.dto.AdminPaymentPartialRefundTarget;
/** 외부 잠금을 가진 채 별도 준비 트랜잭션을 시작하는 호출을 차단한다. */
@Service
@RequiredArgsConstructor
public class AdminRefundPreparationService {
    private final AdminRefundPreparationTransactionService transactions;
    /** 결제액 환불의 독립 준비 트랜잭션을 실행한다. */
    public AdminRefundPrepared prepareFull(String eventId, String organizationId, List<String> ids, AdminRefundCommandContext command) {
        requireNoTransaction();
        return transactions.prepareFull(eventId, organizationId, ids, command);
    }
    /** 결제액 부분환불은 별도 메서드로 진입하여 정책 기반 후보만 처리한다. */
    public AdminRefundPrepared preparePartial(String eventId, String organizationId, List<AdminPaymentPartialRefundTarget> targets,
            AdminRefundCommandContext command) {
        requireNoTransaction();
        return transactions.preparePartial(eventId, organizationId, targets, command);
    }
    /** 호출자가 배치 전체를 하나의 트랜잭션으로 감싸지 못하게 한다. */
    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("관리자 환불 준비는 외부 트랜잭션 없이 호출해야 합니다.");
        }
    }
}
