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

    /** 반환·재확보 대상의 실제 귀속 목적을 기준으로 관련 주문을 ID 순서로 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("""
        select p
        from Payment p
        left join p.registration personalRegistration
        left join p.organization paymentOrganization
        where (
            p.purpose = :purpose and personalRegistration.id in :registrationIds
        )
        or exists (
            select pa.id from PaymentAllocation pa
            where pa.payment = p
              and pa.registration.id in :registrationIds
              and (pa.allocationPurpose = :purpose
                   or (pa.allocationPurpose is null and p.purpose = :purpose))
        )
        or (
            p.purpose = :purpose
            and not exists (select legacy.id from PaymentAllocation legacy where legacy.payment = p)
            and paymentOrganization.id in (
                select r.organization.id from Registration r
                where r.id in :registrationIds and r.organization is not null
            )
        )
        order by p.id
        """)
    List<Payment> findAllRelatedToRegistrationsForUpdate(
            @Param("registrationIds") Collection<String> registrationIds,
            @Param("purpose") PaymentPurpose purpose
    );

    /**
     * 개인 신청 수정에 관련된 모든 목적의 Payment를 잠금 조회한다.
     *
     * 직접 Registration 연결과 Allocation 귀속을 모두 확인한다.
     * Allocation이 여러 건이어도 같은 Payment는 한 번만 반환한다.
     *
     * 호출자는 대회를 먼저 잠그고, Registration을 읽기 전에 호출해야 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("""
        select p
        from Payment p
        left join p.registration directRegistration
        where (
            directRegistration.id = :registrationId
            and directRegistration.event.id = :eventId
        )
        or exists (
            select pa.id
            from PaymentAllocation pa
            join pa.registration allocatedRegistration
            where pa.payment = p
              and allocatedRegistration.id = :registrationId
              and allocatedRegistration.event.id = :eventId
        )
        order by p.id
        """)
    List<Payment> findAllForPersonalModificationForUpdate(
            @Param("eventId") String eventId,
            @Param("registrationId") String registrationId
    );

    /**
     * 단체 수정에 관련된 모든 목적의 Payment를 잠금 조회한다.
     *
     * 단체 직접 주문, 구성원 직접 주문, Allocation 귀속 주문을 포함한다.
     * 삭제된 구성원의 진행 중 금융 처리도 놓치지 않도록
     * Registration의 소프트 삭제 조건은 적용하지 않는다.
     *
     * 호출자는 대회를 먼저 잠그고, 구성원을 읽기 전에 호출해야 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("""
        select p
        from Payment p
        left join p.organization directOrganization
        left join p.registration directRegistration
        left join directRegistration.organization memberOrganization
        where (
            directOrganization.id = :organizationId
            and directOrganization.event.id = :eventId
        )
        or (
            memberOrganization.id = :organizationId
            and directRegistration.event.id = :eventId
        )
        or exists (
            select pa.id
            from PaymentAllocation pa
            join pa.registration allocatedRegistration
            join allocatedRegistration.organization allocatedOrganization
            where pa.payment = p
              and allocatedOrganization.id = :organizationId
              and allocatedRegistration.event.id = :eventId
        )
        order by p.id
        """)
    List<Payment> findAllForOrganizationModificationForUpdate(
            @Param("eventId") String eventId,
            @Param("organizationId") String organizationId
    );
}