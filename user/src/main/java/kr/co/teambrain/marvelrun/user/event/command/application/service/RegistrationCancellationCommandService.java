package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.payment.command.application.refund.ModificationRefundExecutor;
import kr.co.teambrain.marvelrun.user.payment.command.application.refund.ModificationRefundResultReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 참가 취소 커밋 후 기존 환불 실행기를 호출한다. 외부 통신 중 DB 트랜잭션을 유지하지 않는다. */
@Service
@RequiredArgsConstructor
public class RegistrationCancellationCommandService {
    private final RegistrationCancellationTransactionService transactions;
    private final ModificationRefundExecutor refunds;
    private final ModificationRefundResultReader results;

    /** 개인 신청을 취소하고 실제 저장된 환불 결과를 반환한다. */
    public RegistrationModificationSettlementResult cancelPersonal(String eventId, String registrationId,
            RegistrationAccessRequest access) {
        requireNoTransaction();
        return finish(eventId, null, transactions.cancelPersonal(eventId, registrationId, access));
    }

    /** DB가 확정한 단체 전체 구성원을 취소하고 원결제별 환불 결과를 반환한다. */
    public RegistrationModificationSettlementResult cancelOrganization(String eventId, String organizationId,
            OrganizationAccessRequest access) {
        requireNoTransaction();
        return finish(eventId, organizationId, transactions.cancelOrganization(eventId, organizationId, access));
    }

    /** 재요청은 외부 취소를 재전송하지 않고 저장된 상태만 읽는다. */
    private RegistrationModificationSettlementResult finish(String eventId, String organizationId,
            RegistrationCancellationTransactionService.Prepared prepared) {
        if (prepared.result().refunds().isEmpty()) { return prepared.result(); }
        if (prepared.executeRefunds()) { refunds.execute(eventId, organizationId, prepared.result().refunds()); }
        return results.read(prepared.result());
    }

    /** 호출자가 취소 준비와 외부 환불을 하나의 DB 트랜잭션으로 감싸는 것을 차단한다. */
    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new CustomException(ErrorCode.PAYMENT_CANCEL_CONFLICT);
        }
    }
}
