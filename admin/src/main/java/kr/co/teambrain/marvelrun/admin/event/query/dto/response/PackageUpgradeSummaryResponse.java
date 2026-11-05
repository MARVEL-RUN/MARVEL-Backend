package kr.co.teambrain.marvelrun.admin.event.query.dto.response;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.PackageUpgrade;
import kr.co.teambrain.marvelrun.common.json_object.PackageUpgradeEntry;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 신청 상세에 보여 줄 진행 중인 상향. 있으면 환불·정보 변경 전에 상향을 먼저 취소해야 한다. */
public record PackageUpgradeSummaryResponse(
        String upgradeId,
        String targetCategoryId,
        String targetCategoryName,
        BigDecimal amount,
        List<PackageUpgradeEntry> addedEntries,
        LocalDateTime requestedAt
) {
    public static PackageUpgradeSummaryResponse from(PackageUpgrade upgrade) {
        return new PackageUpgradeSummaryResponse(upgrade.getId(), upgrade.getTargetCategory().getId(),
                upgrade.getTargetCategory().getName(), upgrade.getAmount(), upgrade.getAddedEntries(),
                upgrade.getCreatedAt());
    }
}
