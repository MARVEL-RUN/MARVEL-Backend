package kr.co.teambrain.marvelrun.user.event.query.repository;

import jakarta.persistence.EntityManager;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Souvenir;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.PaymentCancel;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.PaymentAllocation;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.PaymentCancelAllocation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 신청 조회 전용 JPQL을 모으며 저장·쓰기 잠금·상태 전이를 수행하지 않는다. */
@Repository
@RequiredArgsConstructor
public class RegistrationReceiptQueryRepository {

    private final EntityManager entityManager;

    /** 개인 신청 후보를 찾는다. 비밀번호는 SQL 파라미터로 보내지 않는다. */
    public List<Registration> findPersonalCandidates(
            String eventId, String name, String birth, String phNum) {
        return entityManager.createQuery("""
                select r from Registration r
                join fetch r.event e
                join fetch r.eventCategory ec
                where e.id = :eventId and r.organization is null
                  and r.name = :name and r.birth = :birth and r.phNum = :phNum
                order by r.registrationDate desc, r.id
                """, Registration.class)
                .setParameter("eventId", eventId).setParameter("name", name)
                .setParameter("birth", birth).setParameter("phNum", phNum)
                .getResultList();
    }

    /** 대회 내 단체 로그인 ID 후보를 찾고 비밀번호 비교는 서비스에서 수행한다. */
    public List<Organization> findOrganizationCandidates(String eventId, String loginId) {
        return entityManager.createQuery("""
                select o from Organization o join fetch o.event e
                where e.id = :eventId and o.loginId = :loginId
                order by o.id
                """, Organization.class)
                .setParameter("eventId", eventId).setParameter("loginId", loginId)
                .getResultList();
    }

    /** 현재·제거 구성원을 함께 읽어 서비스에서 서로 다른 배열로 구분한다. */
    public List<Registration> findOrganizationMembers(String eventId, String organizationId) {
        return entityManager.createQuery("""
                select r from Registration r
                join fetch r.eventCategory ec
                where r.event.id = :eventId and r.organization.id = :organizationId
                order by r.registrationDate, r.id
                """, Registration.class)
                .setParameter("eventId", eventId)
                .setParameter("organizationId", organizationId)
                .getResultList();
    }

    /** 인증을 마친 개인의 직접 소유 주문만 반환한다. */
    public List<Payment> findPersonalPayments(String registrationId) {
        return entityManager.createQuery("""
                select p from Payment p
                where p.registration.id = :registrationId and p.organization is null
                order by p.createdAt desc, p.id
                """, Payment.class)
                .setParameter("registrationId", registrationId).getResultList();
    }

    /** 인증을 마친 단체의 주문을 현재 결제 상태와 관계없이 반환한다. */
    public List<Payment> findOrganizationPayments(String organizationId) {
        return entityManager.createQuery("""
                select p from Payment p where p.organization.id = :organizationId
                order by p.createdAt desc, p.id
                """, Payment.class)
                .setParameter("organizationId", organizationId).getResultList();
    }

    /** 신청별 반복 조회 없이 예약 상태를 한 번에 읽는다. */
    public List<Reservation> findReservations(List<String> registrationIds) {
        if (registrationIds.isEmpty()) {
            return List.of();
        }
        return entityManager.createQuery("""
                select r from Reservation r join fetch r.registration registration
                where registration.id in :ids
                """, Reservation.class)
                .setParameter("ids", registrationIds).getResultList();
    }

    /** 조회 권한을 확인한 주문 목록에 한정하여 원결제 귀속을 읽는다. */
    public List<PaymentAllocation> findAllocations(List<String> paymentIds) {
        if (paymentIds.isEmpty()) {
            return List.of();
        }
        return entityManager.createQuery("""
                select a from PaymentAllocation a
                join fetch a.payment p join fetch a.registration r
                where p.id in :ids order by a.id
                """, PaymentAllocation.class)
                .setParameter("ids", paymentIds).getResultList();
    }

    /** 성공한 환불만이 아니라 실패·확인 중 환불도 원 주문별로 조회한다. */
    public List<PaymentCancel> findRefunds(List<String> paymentIds) {
        if (paymentIds.isEmpty()) {
            return List.of();
        }
        return entityManager.createQuery("""
                select c from PaymentCancel c join fetch c.payment p
                where p.id in :ids order by c.createdAt, c.id
                """, PaymentCancel.class)
                .setParameter("ids", paymentIds).getResultList();
    }

    /** 환불 시도와 원결제 귀속을 함께 읽어 참가자별 환불 대상을 표시한다. */
    public List<PaymentCancelAllocation> findRefundAllocations(List<String> paymentIds) {
        if (paymentIds.isEmpty()) {
            return List.of();
        }
        return entityManager.createQuery("""
                select a from PaymentCancelAllocation a
                join fetch a.paymentCancel c
                join fetch a.originalAllocation original
                join fetch original.registration registration
                where c.payment.id in :ids order by a.id
                """, PaymentCancelAllocation.class)
                .setParameter("ids", paymentIds).getResultList();
    }
    /** 같은 대회의 비활성 기념품도 과거 선택을 표시할 수 있도록 이름을 조회한다. */
    public List<Souvenir> findSouvenirs(String eventId, List<String> souvenirIds) {
        if (souvenirIds.isEmpty()) {
            return List.of();
        }
        return entityManager.createQuery("""
                select s from Souvenir s
                where s.event.id = :eventId and s.id in :ids
                """, Souvenir.class)
                .setParameter("eventId", eventId)
                .setParameter("ids", souvenirIds).getResultList();
    }
}
