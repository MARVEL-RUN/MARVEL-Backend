package kr.co.teambrain.marvelrun.admin.event.query.service;

import kr.co.teambrain.marvelrun.admin.event.query.dto.PendingPackageUpgradeItem;
import kr.co.teambrain.marvelrun.admin.event.query.repository.PackageUpgradeQueryRepository;
import kr.co.teambrain.marvelrun.common.inheritance_enum.upgrade.PackageUpgradeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 관리자 상향 대기 조회. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminPackageUpgradeQueryService {

    private final PackageUpgradeQueryRepository upgrades;

    public List<PendingPackageUpgradeItem> findPending(String eventId) {
        return upgrades.findPendingForAdminList(eventId, PackageUpgradeStatus.PENDING);
    }
}
