package kr.co.teambrain.marvelrun.admin.event.query.dto;

import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.json_object.PackageUpgradeEntry;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자 상향 대기 목록 한 줄. 마지막 상향 주문의 결제 상태로 승인 중·결과 미확인 건을 찾는다.
 * UNKNOWN이면 Toss 관리자 화면에서 결과를 확인한 뒤 처리한다.
 */
public record PendingPackageUpgradeItem(
        String upgradeId,
        String registrationId,
        String name,
        String phNum,
        String currentCategoryName,
        String targetCategoryId,
        String targetCategoryName,
        BigDecimal amount,
        LocalDateTime registrationDate,
        LocalDateTime requestedAt,
        String lastPaymentId,
        String lastOrderId,
        PaymentProcessStatus lastPaymentStatus,
        List<PackageUpgradeEntry> addedEntries
) {
}
