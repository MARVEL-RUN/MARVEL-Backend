package kr.co.teambrain.marvelrun.user.event.query.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GuardianBase;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.PaymentCancelStatus;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 필요한 스칼라 컬럼을 Tuple 프로젝션으로 읽으며 엔티티 find·fetch join·쓰기 잠금을 사용하지 않는다. */
@Repository
@RequiredArgsConstructor
public class RegistrationQueryRepository {
    private final EntityManager entityManager;

    private static final String MEMBER_SELECT = """
            select r.id as id, r.name as name, r.birth as birth, r.phNum as phNum,
                   r.password as password, r.gender as gender,
                   c.id as categoryId, c.name as categoryName, r.souvenirJson as souvenirs,
                   r.address as address, r.addressDetail as addressDetail,
                   r.guardianBase as guardianBase, r.guardianConsent as guardianConsent, r.guardianName as guardianName, r.guardianRelationship as guardianRelationship,
                   r.guardianPhNum as guardianPhNum, r.status as status,
                   r.contractAmount as contractAmount, r.paidAmount as paidAmount,
                   r.softDeleted as deleted, v.status as reservationStatus,
                   e.paymentDeadline as paymentDeadline
            from Registration r join r.event e join r.eventCategory c
            left join Reservation v on v.registration.id = r.id
            """;

    /** 같은 본인정보의 개인 신청을 읽고 실제 비밀번호 비교는 공통 검증기로 수행한다. */
    public List<RegistrationQueryData.Member> personal(String eventId, RegistrationAccessRequest access) {
        return entityManager.createQuery(MEMBER_SELECT + """
                where e.id = :eventId and r.organization is null
                  and r.softDeleted = false
                  and r.name = :name and r.birth = :birth and r.phNum = :phNum
                order by r.registrationDate desc, r.id
                """, Tuple.class)
                .setParameter("eventId", eventId).setParameter("name", access.name())
                .setParameter("birth", access.birth()).setParameter("phNum", access.phNum())
                .getResultList().stream().map(this::member).toList();
    }

    /** 대회 내 단체 계정 후보를 읽으며 엔티티를 영속성 컨텍스트에 적재하지 않는다. */
    public List<RegistrationQueryData.Organization> organizations(String eventId, String loginId) {
        return entityManager.createQuery("""
                select o.id as id, o.loginId as loginId, o.password as password,
                       o.groupName as name, o.leaderName as leaderName, o.leaderBirth as birth,
                       o.leaderPhNum as phNum, o.email as email, o.address as address,
                       o.addressDetail as addressDetail, e.paymentDeadline as paymentDeadline
                from Organization o join o.event e
                where e.id = :eventId and o.loginId = :loginId order by o.id
                """, Tuple.class).setParameter("eventId", eventId).setParameter("loginId", loginId)
                .getResultList().stream().map(t -> new RegistrationQueryData.Organization(
                        t.get("id", String.class), t.get("loginId", String.class), t.get("password", String.class),
                        t.get("name", String.class), t.get("leaderName", String.class), t.get("birth", String.class),
                        t.get("phNum", String.class), t.get("email", String.class), t.get("address", String.class),
                        t.get("addressDetail", String.class), t.get("paymentDeadline", LocalDateTime.class))).toList();
    }

    /** 단체의 조회 당시 활성 구성원 전체를 한 번에 읽는다. 제거·취소 행은 포함하지 않는다. */
    public List<RegistrationQueryData.Member> members(String eventId, String organizationId) {
        return entityManager.createQuery(MEMBER_SELECT + """
                where e.id = :eventId and r.organization.id = :organizationId
                  and r.softDeleted = false order by r.registrationDate, r.id
                """, Tuple.class).setParameter("eventId", eventId)
                .setParameter("organizationId", organizationId)
                .getResultList().stream().map(this::member).toList();
    }

    /** 인증을 마친 대상의 주문을 최신순으로 읽으며 키·PG 원문·로그는 조회하지 않는다. */
    public List<RegistrationQueryData.Payment> payments(String eventId, String targetId, boolean group) {
        String scope = group ? "p.organization.id = :targetId and p.organization.event.id = :eventId"
                : "p.registration.id = :targetId and p.registration.event.id = :eventId and p.organization is null";
        return entityManager.createQuery("""
                select p.id as id, target.id as registrationId, p.orderId as orderId, p.amount as amount,
                       p.purpose as purpose, p.processStatus as status
                from Payment p left join p.registration target where
                """ + scope + " order by p.createdAt desc, p.id", Tuple.class)
                .setParameter("targetId", targetId).setParameter("eventId", eventId)
                .getResultList().stream().map(t -> new RegistrationQueryData.Payment(
                        t.get("id", String.class), t.get("registrationId", String.class), t.get("orderId", String.class), t.get("amount", BigDecimal.class),
                        t.get("purpose", PaymentPurpose.class), t.get("status", PaymentProcessStatus.class))).toList();
    }

    /** 개인 취소·재신청 내역도 대상 전체를 한 번에 조회하여 반복 SELECT를 피한다. */
    public List<RegistrationQueryData.Payment> personalPayments(String eventId, List<String> ids) {
        if (ids.isEmpty()) { return List.of(); }
        return entityManager.createQuery("""
                select p.id as id, target.id as registrationId, p.orderId as orderId,
                       p.amount as amount, p.purpose as purpose, p.processStatus as status
                from Payment p join p.registration target
                where target.event.id = :eventId and target.id in :ids and p.organization is null
                order by p.createdAt desc, p.id
                """, Tuple.class).setParameter("eventId", eventId).setParameter("ids", ids)
                .getResultList().stream().map(t -> new RegistrationQueryData.Payment(
                        t.get("id", String.class), t.get("registrationId", String.class),
                        t.get("orderId", String.class), t.get("amount", BigDecimal.class),
                        t.get("purpose", PaymentPurpose.class), t.get("status", PaymentProcessStatus.class))).toList();
    }

    /** 주문별 개별 조회 대신 귀속값을 한 번에 읽는다. */
    public List<RegistrationQueryData.Allocation> allocations(List<String> paymentIds) {
        if (paymentIds.isEmpty()) { return List.of(); }
        return entityManager.createQuery("""
                select a.payment.id as paymentId, a.registration.id as registrationId,
                       a.allocatedAmount as amount, a.allocationPurpose as purpose
                from PaymentAllocation a where a.payment.id in :ids
                """, Tuple.class).setParameter("ids", paymentIds).getResultList().stream()
                .map(t -> new RegistrationQueryData.Allocation(t.get("paymentId", String.class),
                        t.get("registrationId", String.class), t.get("amount", BigDecimal.class),
                        t.get("purpose", PaymentPurpose.class))).toList();
    }

    /** 단체에서 제거된 사람의 환불이라도 같은 결제 범위의 진행 중 상태는 내부 판단에 포함한다. */
    public List<RegistrationQueryData.Refund> refunds(List<String> paymentIds) {
        if (paymentIds.isEmpty()) { return List.of(); }
        return entityManager.createQuery("""
                select c.payment.id as paymentId, c.status as status from PaymentCancel c
                where c.payment.id in :ids order by c.createdAt desc, c.id
                """, Tuple.class).setParameter("ids", paymentIds).getResultList().stream()
                .map(t -> new RegistrationQueryData.Refund(t.get("paymentId", String.class), t.get("status", PaymentCancelStatus.class))).toList();
    }

    /** 선택된 기념품 이름을 한 번에 읽고 비활성 상품의 과거 선택도 표시한다. */
    public List<RegistrationQueryData.Souvenir> souvenirs(String eventId, List<String> ids) {
        if (ids.isEmpty()) { return List.of(); }
        return entityManager.createQuery("""
                select s.id as id, s.name as name from Souvenir s
                where s.event.id = :eventId and s.id in :ids
                """, Tuple.class).setParameter("eventId", eventId).setParameter("ids", ids)
                .getResultList().stream().map(t -> new RegistrationQueryData.Souvenir(
                        t.get("id", String.class), t.get("name", String.class))).toList();
    }

    /** JSON basic 컬럼을 포함한 스칼라 행을 내부 프로젝션으로 옮긴다. */
    @SuppressWarnings("unchecked")
    private RegistrationQueryData.Member member(Tuple t) {
        List<SouvenirJson> selections = (List<SouvenirJson>) t.get("souvenirs");
        return new RegistrationQueryData.Member(
                t.get("id", String.class), t.get("name", String.class), t.get("birth", String.class),
                t.get("phNum", String.class), t.get("password", String.class), t.get("gender", GenderClass.class),
                t.get("categoryId", String.class), t.get("categoryName", String.class),
                selections == null ? List.of() : selections,
                t.get("address", String.class), t.get("addressDetail", String.class),
                t.get("guardianBase", GuardianBase.class),
                t.get("guardianConsent", Boolean.class),t.get("guardianName", String.class), t.get("guardianPhNum", String.class), t.get("guardianRelationship", String.class),
                t.get("status", RegistrationStatus.class),
                t.get("contractAmount", BigDecimal.class), t.get("paidAmount", BigDecimal.class),
                t.get("deleted", Boolean.class), t.get("reservationStatus", ReservationStatus.class),
                t.get("paymentDeadline", LocalDateTime.class));
    }
}
