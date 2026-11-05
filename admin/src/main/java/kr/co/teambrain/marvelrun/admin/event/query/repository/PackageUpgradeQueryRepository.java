package kr.co.teambrain.marvelrun.admin.event.query.repository;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.PackageUpgrade;
import kr.co.teambrain.marvelrun.admin.event.query.dto.PendingPackageUpgradeItem;
import kr.co.teambrain.marvelrun.common.inheritance_enum.upgrade.PackageUpgradeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PackageUpgradeQueryRepository extends JpaRepository<PackageUpgrade, String> {

    /**
     * 관리자 상향 대기 목록. 마지막 상향 주문은 상향 대기에 남긴 payment_id로 읽는다.
     * 결제창을 다시 열 때마다 이 값이 이번 주문으로 바뀐다.
     */
    @Query("""
            select new kr.co.teambrain.marvelrun.admin.event.query.dto.PendingPackageUpgradeItem(
                u.id, r.id, r.name, r.phNum, current.name, target.id, target.name, u.amount,
                r.registrationDate, u.createdAt, p.id, p.orderId, p.processStatus, u.addedEntries
            )
            from PackageUpgrade u
            join u.registration r
            join r.eventCategory current
            join u.targetCategory target
            left join Payment p on p.id = u.paymentId
            where u.event.id = :eventId
              and u.status = :status
            order by u.createdAt, u.id
            """)
    List<PendingPackageUpgradeItem> findPendingForAdminList(
            @Param("eventId") String eventId,
            @Param("status") PackageUpgradeStatus status
    );
}
