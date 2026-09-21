package kr.co.teambrain.marvelrun.user.payment.command.application.creator;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentAllocationTarget;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 잠금과 최신 상태 검증을 마친 신청에서 추가 납부 대상별 금액을 계산한다.
 * 조회·잠금·상태 변경·주문 저장을 수행하지 않는 계산 구성요소다.
 * 최초 미결제 신청은 별도 최초 주문 경로에서 처리한다.
 */
@Component
public class AdditionalPaymentTargetResolver {

    /**
     * 양수인 계약금액-순납부액만 반환하며 구성원 간 환불액을 상계하지 않는다.
     * 삭제된 구성원은 제외하고, 추가 납부가 없는 신청은 결과에 포함하지 않는다.
     * 부족액이 있는데 추가 결제 상태가 아니면 잘못된 호출로 거절한다.
     */
    public List<PaymentAllocationTarget> resolve(List<Registration> registrations) {
        if (registrations == null) {
            throw invalid();
        }

        Set<String> ids = new HashSet<>();
        List<PaymentAllocationTarget> targets = new ArrayList<>();
        for (Registration registration : registrations) {
            if (registration == null || registration.getId() == null
                    || registration.getId().isBlank()
                    || !ids.add(registration.getId())) {
                throw invalid();
            }
            if (registration.isSoftDeleted()) {
                continue;
            }
            BigDecimal contract = registration.getContractAmount();
            BigDecimal paid = registration.getPaidAmount();
            if (contract == null || contract.signum() < 0
                    || paid == null || paid.signum() < 0) {
                throw invalid();
            }
            BigDecimal balance = contract.subtract(paid);
            if (balance.signum() <= 0) {
                continue;
            }
            if (registration.getStatus()
                    != RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED) {
                throw invalid();
            }
            targets.add(new PaymentAllocationTarget(registration, balance));
        }
        targets.sort(Comparator.comparing(target -> target.registration().getId()));
        return List.copyOf(targets);
    }

    /** 잘못된 추가 주문 계산 입력을 기존 금융 정합성 오류로 알린다. */
    private CustomException invalid() {
        return new CustomException(ErrorCode.REGISTRATION_FINANCIAL_STATE_INVALID);
    }
}
