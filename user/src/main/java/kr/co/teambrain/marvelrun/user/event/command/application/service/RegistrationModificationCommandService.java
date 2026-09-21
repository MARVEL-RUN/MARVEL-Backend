package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.payment.command.application.refund.ModificationRefundExecutor;
import kr.co.teambrain.marvelrun.user.payment.command.application.refund.ModificationRefundResultReader;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 기존 수정 API에서 DB 수정 커밋·외부 환불·결과 저장을 순서대로 연결한다. */
@Service
@RequiredArgsConstructor
public class RegistrationModificationCommandService {
    private final RegistrationModificationTransactionService transactions;
    private final ModificationRefundExecutor refunds;
    private final ModificationRefundResultReader results;

    /** 개인 수정 데이터 저장이 커밋된 후 준비된 환불만 실행한다. */
    public RegistrationModificationSettlementResult modifyPersonal(String eventId, String registrationId,
                                                                   RegistrationModificationRequest request) {
        requireNoTransaction();
        return finish(eventId, null, transactions.modifyPersonal(eventId, registrationId, request));
    }

    /** 단체 수정에서 만든 원결제별 환불을 같은 HTTP 요청 안에서 실행한다. */
    public RegistrationModificationSettlementResult modifyOrganization(String eventId, String organizationId,
                                                                       OrgRegistrationModificationRequest request) {
        requireNoTransaction();
        return finish(eventId, organizationId,
                transactions.modifyOrganization(eventId, organizationId, request));
    }

    /** 환불 없는 개인정보·미결제·추가 결제 경로에는 부가 금융 조회를 만들지 않는다. */
    private RegistrationModificationSettlementResult finish(String eventId, String organizationId,
                                                            RegistrationModificationSettlementResult prepared) {
        if (prepared.refunds().isEmpty()) { return prepared; }
        refunds.execute(eventId, organizationId, prepared.refunds());
        return results.read(prepared);
    }

    /** 외부 트랜잭션이 DB 수정과 Toss 호출 전체를 감싸지 못하게 한다. */
    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new CustomException(ErrorCode.PAYMENT_CANCEL_CONFLICT);
        }
    }
}
