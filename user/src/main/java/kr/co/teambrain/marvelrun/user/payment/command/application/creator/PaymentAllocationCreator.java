package kr.co.teambrain.marvelrun.user.payment.command.application.creator;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.PaymentAllocation;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentAllocationCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.dto.PaymentAllocationTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 하나의 Payment 금액을 Registration별 PaymentAllocation으로 분배하여 저장한다.
 *
 * 최초 단체결제와 향후 추가결제에서 공통으로 사용하며,
 * 별도의 Transaction을 생성하지 않고 호출자의 금융 Transaction에 참여한다.
 *
 * Payment 대상과 Registration 귀속 관계, Registration 중복 및
 * Allocation 전체 합계와 Payment.amount의 일치를 저장 전에 검증한다.
 */
@Component
@RequiredArgsConstructor
public class PaymentAllocationCreator {

    private final PaymentAllocationCommandRepository
            paymentAllocationCommandRepository;

    /**
     * Payment에 대한 Registration별 금액 귀속을 생성한다.
     *
     * 개인 Payment는 Payment.registration과 동일한 Registration만 허용하며,
     * 단체 Payment는 Payment.organization에 속한 Registration만 허용한다.
     *
     * 모든 Allocation의 합은 Payment.amount와 정확히 일치해야 한다.
     *
     * @param payment Allocation을 생성할 Payment
     * @param targets Registration별 귀속 대상과 금액
     * @return 저장된 PaymentAllocation 목록
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<PaymentAllocation> create(
            Payment payment,
            List<PaymentAllocationTarget> targets
    ) {

        validatePaymentTarget(
                payment
        );

        validateTargets(
                payment,
                targets
        );

        List<PaymentAllocation> allocations =
                targets.stream()
                        .map(target ->
                                PaymentAllocation.create(
                                        payment,
                                        target.registration(),
                                        target.amount(),
                                        target.allocationPurpose() == null ? payment.getPurpose() : target.allocationPurpose()
                                )
                        )
                        .toList();

        if (payment.getPurpose() == PaymentPurpose.MIXED_PAYMENT
                && (!payment.isOrgPayment()
                    || allocations.stream().noneMatch(a -> a.effectivePurpose() == PaymentPurpose.REGISTRATION_TRY)
                    || allocations.stream().noneMatch(a -> a.effectivePurpose() == PaymentPurpose.ADDITIONAL_PAYMENT)
                    || allocations.stream().anyMatch(a -> a.getAllocatedAmount().signum() <= 0))) {
            throw integrityError(" 혼합 주문은 최초 및 추가 납부의 양수 귀속을 모두 포함해야 합니다.");
        }

        validateTotalAmount(
                payment,
                allocations
        );

        return paymentAllocationCommandRepository.saveAll(
                allocations
        );
    }

    /**
     * Payment가 개인 또는 단체 중 정확히 하나의 결제 대상을 가지는지 검증한다.
     *
     * @param payment 검증 대상 Payment
     */
    private void validatePaymentTarget(
            Payment payment
    ) {

        if (payment == null) {
            throw integrityError(
                    " Payment가 존재하지 않습니다."
            );
        }

        boolean registrationPayment =
                payment.isRegistrationPayment();

        boolean organizationPayment =
                payment.isOrgPayment();

        if (registrationPayment == organizationPayment) {
            throw integrityError(
                    " Payment는 Registration 또는 Organization 중"
                            + " 정확히 하나만 대상으로 가져야 합니다."
                            + " paymentId=" + payment.getId()
            );
        }
    }

    /**
     * Allocation 대상 Registration의 중복과 Payment 귀속관계를 검증한다.
     *
     * @param payment 검증 대상 Payment
     * @param targets Registration별 귀속 대상
     */
    private void validateTargets(
            Payment payment,
            List<PaymentAllocationTarget> targets
    ) {

        if (
                targets == null
                        || targets.isEmpty()
        ) {
            throw integrityError(
                    " PaymentAllocation 대상이 비어 있습니다."
                            + " paymentId=" + payment.getId()
            );
        }

        Set<String> registrationIds =
                new HashSet<>();

        for (PaymentAllocationTarget target : targets) {

            if (
                    target == null
                            || target.registration() == null
                            || target.amount() == null
                            || target.amount().signum() < 0
            ) {
                throw integrityError(
                        " 잘못된 PaymentAllocation 대상이 포함되어 있습니다."
                                + " paymentId=" + payment.getId()
                );
            }

            Registration registration =
                    target.registration();

            String registrationId =
                    registration.getId();

            if (registrationId == null) {
                throw integrityError(
                        " 저장되지 않은 Registration에는 금액을 귀속할 수 없습니다."
                                + " paymentId=" + payment.getId()
                );
            }

            if (!registrationIds.add(registrationId)) {
                throw integrityError(
                        " 동일 Registration이 중복 귀속되었습니다."
                                + " paymentId=" + payment.getId()
                                + ", registrationId=" + registrationId
                );
            }

            validateRegistrationTarget(
                    payment,
                    registration
            );
        }
    }

    /**
     * Payment의 결제 대상과 Allocation 대상 Registration의 관계를 검증한다.
     *
     * 개인 Payment라면 동일 Registration만 허용하며,
     * 단체 Payment라면 해당 Organization 소속 Registration만 허용한다.
     *
     * @param payment Payment
     * @param registration Allocation 대상 Registration
     */
    private void validateRegistrationTarget(
            Payment payment,
            Registration registration
    ) {

        if (payment.isRegistrationPayment()) {

            if (
                    !Objects.equals(
                            payment.getRegistration().getId(),
                            registration.getId()
                    )
            ) {
                throw integrityError(
                        " 개인 Payment의 대상 Registration과"
                                + " Allocation 대상이 일치하지 않습니다."
                                + " paymentId=" + payment.getId()
                                + ", registrationId=" + registration.getId()
                );
            }

            return;
        }

        Organization organization =
                registration.getOrganization();

        if (
                organization == null
                        || !Objects.equals(
                        payment.getOrganization().getId(),
                        organization.getId()
                )
        ) {
            throw integrityError(
                    " 단체 Payment와 Allocation 대상 Registration의"
                            + " Organization이 일치하지 않습니다."
                            + " paymentId=" + payment.getId()
                            + ", registrationId=" + registration.getId()
            );
        }
    }

    /**
     * Allocation 전체 합계가 Payment.amount와 일치하는지 검증한다.
     *
     * BigDecimal scale 차이는 금액 불일치로 취급하지 않는다.
     *
     * @param payment Payment
     * @param allocations 생성할 Allocation 목록
     */
    private void validateTotalAmount(
            Payment payment,
            List<PaymentAllocation> allocations
    ) {

        BigDecimal allocatedTotal =
                allocations.stream()
                        .map(
                                PaymentAllocation::getAllocatedAmount
                        )
                        .reduce(
                                BigDecimal.ZERO,
                                BigDecimal::add
                        );

        if (
                allocatedTotal.compareTo(
                        payment.getAmount()
                ) != 0
        ) {
            throw integrityError(
                    " Payment.amount와 Allocation 합계가 일치하지 않습니다."
                            + " paymentId=" + payment.getId()
                            + ", paymentAmount=" + payment.getAmount()
                            + ", allocationTotal=" + allocatedTotal
            );
        }
    }

    /**
     * PaymentAllocation 금융 정합성 예외를 생성한다.
     *
     * @param detail 내부 확인용 상세 내용
     * @return PaymentAllocation 정합성 예외
     */
    private CustomException integrityError(
            String detail
    ) {
        return new CustomException(
                ErrorCode.PAYMENT_ALLOCATION_INTEGRITY_ERROR,
                detail
        );
    }
}