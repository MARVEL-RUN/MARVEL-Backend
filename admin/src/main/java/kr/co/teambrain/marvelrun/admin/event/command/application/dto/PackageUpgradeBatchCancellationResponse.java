package kr.co.teambrain.marvelrun.admin.event.command.application.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 일괄 취소 결과. 한 번에 최대 50건을 처리하고, remainingCount가 0이 될 때까지 다시 부른다.
 * blockedCount는 승인 중·결과 미확인 결제가 걸려 빠진 건수다. Toss 결과를 확인한 뒤 다시 부른다.
 */
public record PackageUpgradeBatchCancellationResponse(
        LocalDateTime deadline,
        int processedCount,
        int canceledCount,
        int failedCount,
        long remainingCount,
        long blockedCount,
        List<PackageUpgradeCancellationResult> results
) {
}
