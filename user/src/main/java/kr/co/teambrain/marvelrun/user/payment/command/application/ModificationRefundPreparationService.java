package kr.co.teambrain.marvelrun.user.payment.command.application;

import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_log.PaymentProcessSource;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_log.PaymentProcessType;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.PaymentCancel;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult.Refund;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.ModificationRefundPlanner;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.PaymentCancelAllocationCreator;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.PaymentProcessLog;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.*;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.RefundPaymentLedger;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.RefundPreparationPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 기존 FULL 수정 트랜잭션에 환불 시도·귀속·추적 로그 저장을 연결한다. Toss는 호출하지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ModificationRefundPreparationService {
    private final PaymentCommandRepository paymentRepository;
    private final PaymentAllocationCommandRepository allocationRepository;
    private final PaymentCancelCommandRepository cancelRepository;
    private final PaymentCancelAllocationCommandRepository cancelAllocationRepository;
    private final PaymentProcessLogCommandRepository logRepository;
    private final ModificationRefundPlanner planner;
    private final PaymentCancelAllocationCreator allocationCreator;

    /**
     * 접근·수정 검증과 관련 부모 Payment 잠금을 마친 호출자만 사용한다.
     * 재조회는 같은 부모 잠금을 재사용하며 수정 중 엔티티를 refresh하지 않는다.
     */
    public List<Refund> prepare(String eventId, String organizationId, List<Registration> registrations) {
        boolean required = registrations.stream().anyMatch(r -> r.getPaidAmount().compareTo(r.getContractAmount()) > 0);
        if (!required) { return List.of(); }
        List<Payment> payments = organizationId == null
                ? paymentRepository.findAllForPersonalModificationForUpdate(eventId, registrations.get(0).getId())
                : paymentRepository.findAllForOrganizationModificationForUpdate(eventId, organizationId);
        List<String> ids = payments.stream().map(Payment::getId).distinct().sorted().toList();
        List<PaymentCancel> cancellations = ids.isEmpty() ? List.of() : cancelRepository.findAllByPaymentIdsForUpdate(ids);
        List<RefundPaymentLedger> ledgers = new ArrayList<>();
        for (Payment payment : payments) {
            ledgers.add(new RefundPaymentLedger(payment, allocationRepository.findAllForRefund(payment.getId()),
                    cancellations.stream().filter(c -> c.getPayment().getId().equals(payment.getId())).toList(),
                    cancelAllocationRepository.findAllForRefund(payment.getId())));
        }
        // 원 결제 전체의 배분 검증이 끝난 뒤 저장을 시작한다.
        List<RefundPreparationPlan> plans = planner.plan(registrations, ledgers);
        String correlationId = UUID.randomUUID().toString();
        List<Refund> result = new ArrayList<>();
        for (RefundPreparationPlan plan : plans) {
            PaymentCancel cancellation = cancelRepository.save(PaymentCancel.preparePriceAdjustment(
                    plan.payment(), plan.amount(), plan.type(), "refund-" + UUID.randomUUID()));
            allocationCreator.create(cancellation, plan.targets());
            logRepository.save(PaymentProcessLog.builder()
                    .paymentId(plan.payment().getId()).paymentCancelId(cancellation.getId())
                    .orderId(plan.payment().getOrderId()).correlationId(correlationId)
                    .processType(PaymentProcessType.CANCEL_PREPARED).source(PaymentProcessSource.API)
                    .metadata(Map.of("amount", plan.amount(), "allocationCount", plan.targets().size()))
                    .build());
            result.add(new Refund(cancellation.getId(), plan.payment().getId(), plan.amount(),
                    cancellation.getStatus(), correlationId));
        }
        cancelRepository.flush();
        return List.copyOf(result);
    }
}