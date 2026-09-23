package kr.co.teambrain.marvelrun.admin.event.query.repository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationStatDto;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RegistrationQueryRepository extends JpaRepository<Registration, String>, JpaSpecificationExecutor<Registration> {

    // 특정 단체의 유효한(softDeleted=false) 회원 수 카운트
    long countByOrganizationIdAndSoftDeletedFalse(String organizationId);

    @EntityGraph(attributePaths = {"eventCategory"})
    List<Registration> findByOrganizationIdAndSoftDeletedFalse(String organizationId);

    // 기존 코드 하단에 추가
    /**
     * 신청자별 완료 결제수단을 개인 직접 귀속 또는 PaymentAllocation으로 조회한다.
     * 단체 주문은 registration이 없으므로 구성원별 귀속 원장을 확인한다.
     * 외부 조회는 Registration 한 행을 유지하여 복수 결제/추가 결제에도 중복 집계하지 않는다.
     * 단체 ID만으로 연결하면 미납 구성원까지 입금자로 분류되므로 사용하지 않는다.
     */
    @Query("""
        select new kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationStatDto(
            r.status, 
            o.id, 
            r.gender, 
            r.birth,
            c.name, 
            r.contractAmount,
            (select p.paymentMethod
             from Payment p
             where p.processStatus = 'COMPLETED'
               and (
                   p.registration.id = r.id
                   or exists (
                       select a.id
                       from PaymentAllocation a
                       where a.payment.id = p.id
                         and a.registration.id = r.id
                   )
               )
             order by p.createdAt desc, p.id desc
             limit 1)
        )
        from Registration r
        join r.eventCategory c
        left join r.organization o
        where r.event.id = :eventId
          and r.softDeleted = false
        """)
    List<RegistrationStatDto> findStatsByEventId(@Param("eventId") String eventId);
}
