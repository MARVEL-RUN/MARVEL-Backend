package kr.co.teambrain.marvelrun.user.event.command.application.service;

import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository.PaymentCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 신청 수정과 기존 결제 승인 간 충돌을 차단한다.
 *
 * 대회 잠금 다음에 관련 Payment를 잠그고,
 * 본인확인이 완료되면 상태 검증과 READY 주문 무효화를 수행한다.
 *
 * 잠금·무효화·신청 수정은 반드시 같은 트랜잭션에 속해야 한다.
 * 실제 승인·환불 요청과 새 주문 생성은 수행하지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class RegistrationModificationPaymentGuard {

    private final PaymentCommandRepository paymentRepository;

    /**
     * 개인 신청 관련 Payment를 잠근다.
     *
     * Registration 본인확인 조회보다 먼저 호출한다.
     * 반환한 Entity 목록은 같은 수정 트랜잭션 안에서만 사용한다.
     */
    public List<Payment> lockPersonal(
            String eventId,
            String registrationId
    ) {
        return paymentRepository.findAllForPersonalModificationForUpdate(
                eventId,
                registrationId
        );
    }

    /**
     * 단체 및 구성원 관련 Payment를 잠근다.
     *
     * 현재 구성원 조회보다 먼저 호출하며 모든 결제 목적을 포함한다.
     */
    public List<Payment> lockOrganization(
            String eventId,
            String organizationId
    ) {
        return paymentRepository.findAllForOrganizationModificationForUpdate(
                eventId,
                organizationId
        );
    }

    /**
     * 본인확인 후 잠긴 Payment 전체를 검증하고 READY 주문을 무효화한다.
     *
     * 이 서비스의 잠금 조회로 얻은 목록만 전달해야 한다.
     * 하나라도 충돌 상태이면 다른 주문도 변경하지 않는다.
     *
     * 이후 정책검증·Capacity 이동·신청 저장이 실패하면
     * READY 무효화 역시 같은 트랜잭션에서 롤백된다.
     */
    public void prepareLockedPayments(List<Payment> lockedPayments) {
        for (Payment payment : lockedPayments) {
            payment.validateRegistrationModificationAllowed();
        }

        for (Payment payment : lockedPayments) {
            payment.invalidateForRegistrationModification();
        }

        if (!lockedPayments.isEmpty()) {
            paymentRepository.flush();
        }
    }
}