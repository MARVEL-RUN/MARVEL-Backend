package kr.co.teambrain.marvelrun.user.event.command.repository;

import jakarta.persistence.LockModeType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.upgrade.PackageUpgradeStatus;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.PackageUpgrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PackageUpgradeCommandRepository extends JpaRepository<PackageUpgrade, String> {

    /** 신청의 진행 중인 상향. 신청마다 하나뿐이다(uk_package_upgrade_active). 호출자는 신청을 먼저 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select u from PackageUpgrade u
            where u.registration.id = :registrationId
              and u.status = :status
            """)
    Optional<PackageUpgrade> findByRegistrationForUpdate(
            @Param("registrationId") String registrationId,
            @Param("status") PackageUpgradeStatus status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select u from PackageUpgrade u
            where u.registration.id in :registrationIds
              and u.status = :status
            order by u.id
            """)
    List<PackageUpgrade> findAllByRegistrationsForUpdate(
            @Param("registrationIds") Collection<String> registrationIds,
            @Param("status") PackageUpgradeStatus status
    );

    /** 조회 응답용. 잠그지 않는다. */
    @Query("""
            select u from PackageUpgrade u
            join fetch u.targetCategory
            where u.registration.id in :registrationIds
              and u.status = :status
            """)
    List<PackageUpgrade> findAllByRegistrations(
            @Param("registrationIds") Collection<String> registrationIds,
            @Param("status") PackageUpgradeStatus status
    );
}
