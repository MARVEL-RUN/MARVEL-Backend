package kr.co.teambrain.marvelrun.admin.event.command.application.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 일괄 취소 결과. 한 번에 최대 50건을 처리한다.
 * nextAfterUpgradeId를 다음 요청의 afterUpgradeId로 넘겨, 이 값이 null이 될 때까지 다시 부른다.
 * remainingCount는 그 커서 뒤에 남은 취소할 수 있는 건수다. 실패한 건은 PENDING으로 남으며 결과 목록의 사유를 보고 단건으로 처리한다.
 * blockedCount는 승인 중·결과 미확인 결제가 걸려 빠진 건수다. Toss 결과를 확인한 뒤 처음부터 다시 부른다.
 */
public record PackageUpgradeBatchCancellationResponse(
        LocalDateTime deadline,
        int processedCount,
        int canceledCount,
        int failedCount,
        long remainingCount,
        long blockedCount,
        String nextAfterUpgradeId,
        List<PackageUpgradeCancellationResult> results
) {
}
