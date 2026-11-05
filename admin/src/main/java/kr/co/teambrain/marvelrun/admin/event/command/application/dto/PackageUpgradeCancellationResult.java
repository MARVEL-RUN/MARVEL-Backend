package kr.co.teambrain.marvelrun.admin.event.command.application.dto;

import kr.co.teambrain.marvelrun.admin.event.command.application.domain.PackageUpgrade;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;

/** 상향 대기 한 건의 관리자 취소 결과. */
public record PackageUpgradeCancellationResult(
        String upgradeId,
        String registrationId,
        String name,
        boolean canceled,
        String message
) {
    public static PackageUpgradeCancellationResult canceled(PackageUpgrade upgrade, Registration registration) {
        return new PackageUpgradeCancellationResult(upgrade.getId(), registration.getId(), registration.getName(),
                true, "상향을 취소하고 잡아 둔 정원을 돌려줬습니다.");
    }

    public static PackageUpgradeCancellationResult skipped(PackageUpgrade upgrade, Registration registration, String message) {
        return new PackageUpgradeCancellationResult(upgrade.getId(), registration.getId(), registration.getName(),
                false, message);
    }

    public static PackageUpgradeCancellationResult failed(String upgradeId, String message) {
        return new PackageUpgradeCancellationResult(upgradeId, null, null, false, message);
    }
}
