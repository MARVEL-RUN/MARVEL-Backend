package kr.co.teambrain.marvelrun.admin.payment.command;

import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.admin.payment.command.AdminRefundLockedScope.*;

/** 한 개인 또는 한 단체의 제한된 대상 명단을 같은 쓰기 트랜잭션에서 보호한다. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class AdminRefundAccessService {
    private final AdminRefundLockRepository repository;
    public static final int MAX_TARGETS = 100;

    /**
     * 호출자는 인증된 관리자이며 대회 권한 확인을 마친 상태여야 한다.
     * 개인은 1명, 단체는 동일 단체의 1~100명만 입력한다. 단체 전체 명단 확장은 배치 책임이다.
     * 반환 후 같은 트랜잭션에서 정책 계산·원귀속 대사·환불 준비를 마쳐야 한다.
     * 실패는 트랜잭션 밖 배치 실행자가 수집한다. 여기서 Toss를 호출하거나 예외를 삼키지 않는다.
     */
    public AdminRefundLockedScope lock(String eventId, String organizationId, List<String> registrationIds) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("관리자 환불 접근은 기존 쓰기 트랜잭션이 필요합니다.");
        }
        if (eventId == null || eventId.isBlank() || registrationIds == null || registrationIds.isEmpty()
                || registrationIds.size() > MAX_TARGETS
                || registrationIds.stream().anyMatch(id -> id == null || id.isBlank())
                || (organizationId != null && organizationId.isBlank())
                || (organizationId == null && registrationIds.size() != 1)) {
            throw new IllegalArgumentException("환불 대상 범위는 개인 1명 또는 동일 단체 1~100명입니다.");
        }
        List<String> ids = registrationIds.stream().distinct().sorted().toList();
        if (ids.size() != registrationIds.size()) { throw new IllegalArgumentException("환불 대상이 중복되었습니다."); }
        if (!repository.lockEvent(eventId)) { throw new CustomException(ErrorCode.EVENT_NOT_FOUND); }
        if (organizationId != null && !repository.lockOrganization(eventId, organizationId)) {
            throw new CustomException(ErrorCode.ORGANIZATION_NOT_FOUND);
        }
        List<PaymentRow> payments = repository.lockPayments(eventId, organizationId, ids.get(0));
        checkBound(payments.size());
        for (PaymentRow payment : payments) {
            if (payment.status() == null) { throw new CustomException(ErrorCode.REGISTRATION_MODIFICATION_PAYMENT_CONFLICT); }
            switch (payment.status()) {
                case READY, COMPLETED, FAILED, INVALIDATED -> { }
                default -> throw new CustomException(ErrorCode.REGISTRATION_MODIFICATION_PAYMENT_CONFLICT);
            }
        }
        List<CancelRow> cancellations = repository.lockCancellations(payments.stream().map(PaymentRow::id).sorted().toList());
        checkBound(cancellations.size());
        for (CancelRow cancel : cancellations) {
            if (cancel.status() == null) { throw new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR); }
            if (cancel.status() == PaymentCancelStatus.PROCESSING || cancel.status() == PaymentCancelStatus.UNKNOWN) {
                throw new CustomException(ErrorCode.PAYMENT_CANCEL_CONFLICT);
            }
        }
        List<RegistrationRow> registrations = repository.lockRegistrations(eventId, ids);
        if (!registrations.stream().map(RegistrationRow::id).sorted().toList().equals(ids)) {
            throw new CustomException(ErrorCode.REGISTRATION_NOT_FOUND);
        }
        for (RegistrationRow registration : registrations) {
            if (!Objects.equals(organizationId, registration.organizationId()) || registration.deleted()
                    || registration.status() != RegistrationStatus.CONFIRMED
                    || registration.contractAmount() == null || registration.paidAmount() == null
                    || registration.paidAmount().signum() <= 0
                    || registration.contractAmount().compareTo(registration.paidAmount()) != 0) {
                throw new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
            }
        }
        if (payments.stream().noneMatch(p -> p.status() == PaymentProcessStatus.COMPLETED)) {
            throw new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
        }
        return new AdminRefundLockedScope(eventId, organizationId, registrations, payments, cancellations);
    }

    /** 과도한 원장 이력을 일부만 보고 성공 처리하지 않는다. 후속 배치에서 별도 예외로 수집한다. */
    private static void checkBound(int count) {
        if (count > AdminRefundLockRepository.MAX_LEDGER_ROWS) {
            throw new IllegalArgumentException("원장 이력이 500건을 초과합니다. 전체 이력 검증이 필요합니다.");
        }
    }
}
