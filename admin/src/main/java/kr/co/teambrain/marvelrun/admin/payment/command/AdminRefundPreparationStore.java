package kr.co.teambrain.marvelrun.admin.payment.command;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import kr.co.teambrain.marvelrun.admin.common.exception.*;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.*;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.admin.payment.command.application.domain.PaymentAllocation;
import kr.co.teambrain.marvelrun.admin.payment.command.application.domain.PaymentCancelAllocation;
import kr.co.teambrain.marvelrun.admin.payment.command.application.domain.PaymentProcessLog;
import kr.co.teambrain.marvelrun.admin.payment.command.application.dto.RefundPaymentLedger;

/** 02에서 잠근 행을 현재 읽기로 로드하고 기존 테이블에 준비 결과를 저장한다. */
@Repository
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class AdminRefundPreparationStore {
    private final EntityManager entityManager;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private static final int MAX_ALLOCATION_ROWS = 5000;

    /** 잠긴 엔티티를 refresh하여 영속성 컨텍스트의 과거 값을 사용하지 않는다. */
    public <T> T current(Class<T> type, String id) {
        T value = entityManager.find(type, id);
        if (value == null) { throw invalid(); }
        entityManager.refresh(value, LockModeType.PESSIMISTIC_WRITE);
        return value;
    }

    /** 선택 참가자마다 CONSUMED 예약이 있는지는 준비 서비스에서 검증한다. */
    public List<Reservation> reservations(List<String> ids) {
        List<Reservation> rows = entityManager.createQuery(
                "select r from Reservation r where r.registration.id in :ids order by r.id", Reservation.class)
                .setParameter("ids", ids).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        for (Reservation row : rows) { entityManager.refresh(row, LockModeType.PESSIMISTIC_WRITE); }
        return rows;
    }

    /** Payment와 취소의 잠금은 02가 완료했다. 전체 귀속을 누락 없이 읽되 총량을 제한한다. */
    public List<RefundPaymentLedger> ledgers(AdminRefundLockedScope scope) {
        List<RefundPaymentLedger> result = new ArrayList<>();
        int remaining = MAX_ALLOCATION_ROWS;
        for (var row : scope.payments()) {
            Payment payment = current(Payment.class, row.id());
            List<PaymentCancel> cancels = new ArrayList<>();
            for (var cancel : scope.cancellations()) {
                if (row.id().equals(cancel.paymentId())) { cancels.add(current(PaymentCancel.class, cancel.id())); }
            }
            List<PaymentAllocation> allocations = bounded(entityManager.createQuery(
                    "select a from PaymentAllocation a where a.payment.id=:id order by a.id", PaymentAllocation.class)
                    .setParameter("id", row.id()), remaining);
            remaining -= allocations.size();
            List<PaymentCancelAllocation> cancelAllocations = bounded(entityManager.createQuery(
                    "select a from PaymentCancelAllocation a where a.paymentCancel.payment.id=:id order by a.id", PaymentCancelAllocation.class)
                    .setParameter("id", row.id()), remaining);
            remaining -= cancelAllocations.size();
            result.add(new RefundPaymentLedger(payment, allocations, cancels, cancelAllocations));
        }
        return List.copyOf(result);
    }

    /** 귀속 상한을 넘으면 일부만 사용하지 않고 같은 트랜잭션을 실패시킨다. */
    private <T> List<T> bounded(TypedQuery<T> query, int remaining) {
        List<T> rows = query.setMaxResults(remaining + 1).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
        if (rows.size() > remaining) { throw invalid(); }
        for (T row : rows) { entityManager.refresh(row, LockModeType.PESSIMISTIC_WRITE); }
        return rows;
    }

    /** UUID는 공통 Entity의 기존 생성 규칙을 사용한다. */
    public PaymentCancel save(PaymentCancel cancellation) { entityManager.persist(cancellation); return cancellation; }
    /** 준비 로그는 변경과 같은 트랜잭션에 추가한다. */
    public void log(PaymentProcessLog log) { entityManager.persist(log); }
    /** 데이터 제약 실패도 준비 전체를 롤백시킨다. */
    public void flush() { entityManager.flush(); }
    /** 배치 대상의 변경 스냅샷을 신청 변경과 원자적으로 기록한다. 금융 시도를 새로 만들지 않는다. */
    public void recordAdjustmentPrepared(AdminRefundCommandContext command, AdminRefundPrepared prepared) {
        if (command.batchId() == null) { return; }
        try {
            int count = entityManager.createNativeQuery("""
                    update admin_refund_batch_item set preparation_json=:body
                    where batch_id=:batch and item_no=:item and status='RUNNING'
                    """).setParameter("body", mapper.writeValueAsString(prepared))
                    .setParameter("batch", command.batchId()).setParameter("item", command.batchItemNo()).executeUpdate();
            if (count != 1) { throw invalid(); }
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("관리자 변경 증거 직렬화 실패", error);
        }
    }
    private static CustomException invalid() { return new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR); }
}
