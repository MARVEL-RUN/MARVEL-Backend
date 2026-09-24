package kr.co.teambrain.marvelrun.admin.event.query.repository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationStatDto;
import kr.co.teambrain.marvelrun.admin.event.query.report.RegistrationDailyReportRow;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
     * 귀속 없는 기존 단체원은 양수 계약액 완납·CONFIRMED·단체 완료 주문 1건일 때만 보완한다.
     * 귀속이 있거나 완료 주문이 여러 건이면 단체 결제수단을 추정하지 않는다.
     * 이 보완은 통계 조회 전용이며 금융 원장을 생성하거나 수정하지 않는다.
     */
    @Query("""
        select new kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationStatDto(
            r.status, 
            o.id, 
            r.gender, 
            r.birth,
            c.name, 
            r.contractAmount,
            r.paidAmount,
            coalesce((select p.paymentMethod
             from Payment p
             left join p.registration directRegistration
             where p.processStatus = 'COMPLETED'
               and (
                   directRegistration.id = r.id
                   or exists (
                       select a.id
                       from PaymentAllocation a
                       where a.payment.id = p.id
                         and a.registration.id = r.id
                   )
               )
             order by p.createdAt desc, p.id desc
             limit 1),
            (select legacy.paymentMethod
             from Payment legacy
             where legacy.organization.id = o.id
               and legacy.processStatus = 'COMPLETED'
               and r.status = 'CONFIRMED'
               and r.contractAmount > 0
               and r.paidAmount >= r.contractAmount
               and not exists (
                   select existingAllocation.id from PaymentAllocation existingAllocation
                   where existingAllocation.registration.id = r.id
               )
               and not exists (
                   select directPayment.id from Payment directPayment
                   where directPayment.registration.id = r.id
                     and directPayment.processStatus = 'COMPLETED'
               )
               and (select count(completed.id) from Payment completed
                    where completed.organization.id = o.id
                      and completed.processStatus = 'COMPLETED') = 1
            ))
        )
        from Registration r
        join r.eventCategory c
        left join r.organization o
        where r.event.id = :eventId
          and r.softDeleted = false
        """)
    List<RegistrationStatDto> findStatsByEventId(@Param("eventId") String eventId);

    /**
     * 일별 신청·입금 보고서용 데이터를 조회한다.
     *
     * 신청자는 registrationDate 기준으로 집계하고,
     * 입금자는 신청자에게 귀속된 최초 COMPLETED 결제 시각을 기준으로 집계한다.
     *
     * 직접 Registration 귀속 Payment와 PaymentAllocation 귀속을 모두 지원한다.
     *
     * 기존 데이터 중 Allocation이 없는 단체 신청은
     * 단체 COMPLETED Payment가 정확히 1건인 경우에 한해 해당 결제 시각을 보완한다.
     */
    /**
     * 일별 신청·입금 보고서용 데이터를 조회한다.
     *
     * 결제 귀속 판정은 기존 findStatsByEventId의
     * 직접 귀속 / PaymentAllocation / 레거시 단체 fallback 조건을 그대로 따른다.
     *
     * firstPaidAt은 해당 판정으로 확인되는 최초 완료 결제 시각이다.
     */
    @Query("""
    select new kr.co.teambrain.marvelrun.admin.event.query.report.RegistrationDailyReportRow(
        r.status,
        r.birth,
        c.name,
        r.paidAmount,
        r.registrationDate,

        coalesce(
            (
                select min(p.createdAt)
                from Payment p
                left join p.registration directRegistration
                where p.processStatus = 'COMPLETED'
                  and (
                      directRegistration.id = r.id
                      or exists (
                          select a.id
                          from PaymentAllocation a
                          where a.payment.id = p.id
                            and a.registration.id = r.id
                      )
                  )
            ),

            (
                select legacy.createdAt
                from Payment legacy
                where legacy.organization.id = o.id
                  and legacy.processStatus = 'COMPLETED'

                  and r.status = 'CONFIRMED'
                  and r.contractAmount > 0
                  and r.paidAmount >= r.contractAmount

                  and not exists (
                      select existingAllocation.id
                      from PaymentAllocation existingAllocation
                      where existingAllocation.registration.id = r.id
                  )

                  and not exists (
                      select directPayment.id
                      from Payment directPayment
                      where directPayment.registration.id = r.id
                        and directPayment.processStatus = 'COMPLETED'
                  )

                  and (
                      select count(completed.id)
                      from Payment completed
                      where completed.organization.id = o.id
                        and completed.processStatus = 'COMPLETED'
                  ) = 1
            )
        )
    )
    from Registration r
    join r.eventCategory c
    left join r.organization o
    where r.event.id = :eventId
      and r.softDeleted = false
      and r.registrationDate >= :startDateTime
      and r.registrationDate < :endDateTimeExclusive
    """)
    List<RegistrationDailyReportRow> findDailyReportRows(
            @Param("eventId") String eventId,
            @Param("startDateTime") LocalDateTime startDateTime,
            @Param("endDateTimeExclusive") LocalDateTime endDateTimeExclusive
    );

}

