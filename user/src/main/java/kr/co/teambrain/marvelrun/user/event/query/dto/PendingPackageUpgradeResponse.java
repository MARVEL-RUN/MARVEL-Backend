package kr.co.teambrain.marvelrun.user.event.query.dto;

import kr.co.teambrain.marvelrun.common.json_object.PackageUpgradeEntry;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 진행 중인 단품 → 패키지 상향. 있으면 신청은 「추가 결제 필요」 상태이고 계약금액에 차액이 들어 있다.
 * 결제창은 로그인한 뒤 상향 화면에서 다시 연다.
 */
public record PendingPackageUpgradeResponse(
        String upgradeId,
        String targetCategoryId,
        String targetCategoryName,
        BigDecimal amount,
        List<PackageUpgradeEntry> addedEntries,
        LocalDateTime requestedAt
) {
}
