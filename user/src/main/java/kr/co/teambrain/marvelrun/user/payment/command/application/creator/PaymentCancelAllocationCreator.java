package kr.co.teambrain.marvelrun.user.payment.command.application.creator;

import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.PaymentCancel;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.PaymentCancelAllocation;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentCancelAllocationCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentCancelAllocationTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 취소 시도의 참가자별 귀속을 검증하여 같은 트랜잭션에서 저장한다.
 * 한도 예약·중복 요청 판단·권한·상태 잠금은 호출 서비스의 책임이다.
 */
@Component
@RequiredArgsConstructor
public class PaymentCancelAllocationCreator {
    private final PaymentCancelAllocationCommandRepository repository;

    /**
     * 원결제 일치·중복 귀속·합계 일치를 모두 검증한 후 일괄 저장한다.
     * 기존 취소에 대한 재호출은 허용하지 않는다. 재요청은 기존 결과를 조회해야 한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<PaymentCancelAllocation> create(
            PaymentCancel cancellation,
            List<PaymentCancelAllocationTarget> targets
    ) {
        if (cancellation == null || cancellation.getId() == null
                || cancellation.getStatus() != PaymentCancelStatus.PROCESSING
                || cancellation.getPayment() == null
                || cancellation.getPayment().getProcessStatus() != PaymentProcessStatus.COMPLETED
                || cancellation.getCancelAmount() == null
                || cancellation.getCancelAmount().signum() <= 0
                || targets == null || targets.isEmpty()) {
            throw invalid();
        }

        Set<String> ids = new HashSet<>();
        List<PaymentCancelAllocation> allocations = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (PaymentCancelAllocationTarget target : targets) {
            if (target == null || target.originalAllocation() == null
                    || target.originalAllocation().getId() == null
                    || !ids.add(target.originalAllocation().getId())) {
                throw invalid();
            }
            PaymentCancelAllocation allocation = PaymentCancelAllocation.create(
                    cancellation, target.originalAllocation(), target.amount());
            allocations.add(allocation);
            total = total.add(allocation.getAllocatedAmount());
        }
        if (total.compareTo(cancellation.getCancelAmount()) != 0) {
            throw invalid();
        }
        allocations.sort(Comparator.comparing(
                allocation -> allocation.getOriginalAllocation().getId()));
        if (repository.existsByPaymentCancel_Id(cancellation.getId())) {
            throw invalid();
        }
        return repository.saveAll(allocations);
    }

    /** 취소 원장의 구조 또는 금액 불일치를 알린다. */
    private CustomException invalid() {
        return new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
    }
}