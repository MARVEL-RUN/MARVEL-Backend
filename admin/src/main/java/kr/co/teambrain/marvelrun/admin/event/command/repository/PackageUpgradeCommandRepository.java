package kr.co.teambrain.marvelrun.admin.event.command.repository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.PackageUpgrade;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.upgrade.PackageUpgradeStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface PackageUpgradeCommandRepository extends JpaRepository<PackageUpgrade, String> {

    /** 신청들의 상향 가운데 주어진 상태인 것. 잠그지 않는다(목록·상세·차단 판정의 첫 조회). */
    @Query("""
            select u from PackageUpgrade u
            where u.registration.id in :registrationIds
              and u.status = :status
            """)
    List<PackageUpgrade> findAllByRegistrations(
            @Param("registrationIds") Collection<String> registrationIds,
            @Param("status") PackageUpgradeStatus status
    );

    /**
     * 마감 시각 전에 신청한 신청의 남은 상향 대기 id. 일괄 취소가 건마다 따로 잠가 처리한다.
     * 승인 중·결과 미확인 결제가 걸린 건은 빼서, 앞쪽에 막힌 건이 뒤쪽 처리를 가로막지 않게 한다.
     */
    @Query("""
            select u.id from PackageUpgrade u
            where u.event.id = :eventId
              and u.status = :status
              and u.registration.registrationDate < :deadline
              and not exists (
                  select p.id from Payment p
                  where p.registration = u.registration and p.processStatus in :blocked
              )
            order by u.createdAt, u.id
            """)
    List<String> findCancelableIdsBefore(
            @Param("eventId") String eventId,
            @Param("status") PackageUpgradeStatus status,
            @Param("deadline") LocalDateTime deadline,
            @Param("blocked") Collection<PaymentProcessStatus> blocked,
            Pageable pageable
    );

    @Query("""
            select count(u) from PackageUpgrade u
            where u.event.id = :eventId
              and u.status = :status
              and u.registration.registrationDate < :deadline
              and not exists (
                  select p.id from Payment p
                  where p.registration = u.registration and p.processStatus in :blocked
              )
            """)
    long countCancelableBefore(
            @Param("eventId") String eventId,
            @Param("status") PackageUpgradeStatus status,
            @Param("deadline") LocalDateTime deadline,
            @Param("blocked") Collection<PaymentProcessStatus> blocked
    );

    /** 승인 중·결과 미확인 결제가 걸려 일괄 취소에서 빠진 상향 대기 수. Toss 결과를 확인한 뒤 처리한다. */
    @Query("""
            select count(u) from PackageUpgrade u
            where u.event.id = :eventId
              and u.status = :status
              and u.registration.registrationDate < :deadline
              and exists (
                  select p.id from Payment p
                  where p.registration = u.registration and p.processStatus in :blocked
              )
            """)
    long countBlockedBefore(
            @Param("eventId") String eventId,
            @Param("status") PackageUpgradeStatus status,
            @Param("deadline") LocalDateTime deadline,
            @Param("blocked") Collection<PaymentProcessStatus> blocked
    );

    /** 관리자 대기 목록. 신청·대상 패키지를 함께 읽는다. */
    @Query("""
            select u from PackageUpgrade u
            join fetch u.registration r
            join fetch u.targetCategory
            where u.event.id = :eventId
              and u.status = :status
            order by u.createdAt, u.id
            """)
    List<PackageUpgrade> findAllForAdminList(
            @Param("eventId") String eventId,
            @Param("status") PackageUpgradeStatus status
    );
}
