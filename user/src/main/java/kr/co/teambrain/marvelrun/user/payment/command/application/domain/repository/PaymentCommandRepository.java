package kr.co.teambrain.marvelrun.user.payment.command.application.domain.repository;

import jakarta.persistence.LockModeType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentCommandRepository
        extends JpaRepository<Payment, String> {

    Optional<Payment> findByOrderId(
            String orderId
    );

    /**
     * 승인 시작 대상 Payment를 잠금 조회한다.
     *
     * 호출 서비스의 트랜잭션 안에서 사용하며,
     * 동일 주문에 대한 동시 승인 시작을 순차 처리한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select p
        from Payment p
        where p.orderId = :orderId
        """)
    Optional<Payment> findByOrderIdForUpdate(
            @Param("orderId") String orderId
    );

    /**
     * 승인 결과를 반영할 Payment를 잠금 조회한다.
     *
     * 성공·실패·UNKNOWN 처리 간 상태 덮어쓰기를 방지하기 위해 사용한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select p
        from Payment p
        where p.id = :paymentId
        """)
    Optional<Payment> findByIdForUpdate(
            @Param("paymentId") String paymentId
    );

    /**
     * 개인 신청에 연결된 지정 목적의 주문을 잠금 조회한다.
     *
     * 미결제 확보 반환 시 최초 참가비 주문 전체의 상태를 확인하고,
     * 승인 가능한 기존 주문을 무효화하기 위해 사용한다.
     * 호출자의 트랜잭션이 종료될 때까지 잠금을 유지한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("""
        select p
        from Payment p
        where p.registration.id = :registrationId
          and p.purpose = :purpose
        order by p.id
        """)
    List<Payment> findAllByRegistrationIdAndPurposeForUpdate(
            @Param("registrationId") String registrationId,
            @Param("purpose") PaymentPurpose purpose
    );

    /**
     * 단체에 연결된 지정 목적의 주문을 잠금 조회한다.
     *
     * 단체 미결제 확보 반환 시 단체 주문 전체의 상태를 확인한다.
     * 단체 주문은 organization으로 연결되므로 개인 신청 조회와 구분한다.
     * 호출자의 트랜잭션이 종료될 때까지 잠금을 유지한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("""
        select p
        from Payment p
        where p.organization.id = :organizationId
          and p.purpose = :purpose
        order by p.id
        """)
    List<Payment> findAllByOrganizationIdAndPurposeForUpdate(
            @Param("organizationId") String organizationId,
            @Param("purpose") PaymentPurpose purpose
    );

    /**
     * 반환 대상 신청과 연결된 개인 주문 및 단체 주문을 잠금 조회한다.
     *
     * 단체 구성원 일부만 반환하더라도 해당 구성원을 포함한 기존 단체 주문은
     * 그대로 승인할 수 없으므로 조회 대상에 포함한다.
     *
     * 주문은 Payment ID 순서로 조회하며,
     * 실제 예약 수량의 반환 대상은 전달받은 신청 목록으로 유지한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("""
        select p
        from Payment p
        left join p.registration personalRegistration
        left join p.organization paymentOrganization
        where p.purpose = :purpose
          and (
              personalRegistration.id in :registrationIds
              or paymentOrganization.id in (
                  select r.organization.id
                  from Registration r
                  where r.id in :registrationIds
                    and r.organization is not null
              )
          )
        order by p.id
        """)
    List<Payment> findAllRelatedToRegistrationsForUpdate(
            @Param("registrationIds") Collection<String> registrationIds,
            @Param("purpose") PaymentPurpose purpose
    );
}