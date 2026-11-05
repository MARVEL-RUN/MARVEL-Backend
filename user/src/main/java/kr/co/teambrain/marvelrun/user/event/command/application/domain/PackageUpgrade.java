package kr.co.teambrain.marvelrun.user.event.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.common.entity.PackageUpgradeBase;
import kr.co.teambrain.marvelrun.common.inheritance_enum.upgrade.PackageUpgradeStatus;
import kr.co.teambrain.marvelrun.common.json_object.PackageUpgradeEntry;
import kr.co.teambrain.marvelrun.common.json_object.PackageUpgradeHeldCapacity;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 단품 → 패키지 상향 대기. */
@Getter
@Entity
@SuperBuilder
@Table(name = "package_upgrade")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PackageUpgrade extends PackageUpgradeBase<Event, Registration, EventCategory> {

    public static PackageUpgrade create(
            Registration registration,
            EventCategory packageCategory,
            BigDecimal amount,
            List<PackageUpgradeEntry> addedEntries,
            List<PackageUpgradeHeldCapacity> heldCapacities
    ) {
        return PackageUpgrade.builder()
                .event(registration.getEvent())
                .registration(registration)
                .targetCategory(packageCategory)
                .amount(amount)
                .status(PackageUpgradeStatus.PENDING)
                .addedEntries(List.copyOf(addedEntries))
                .heldCapacities(List.copyOf(heldCapacities))
                .build();
    }

    /** 상향 요청이 만든 첫 주문을 남긴다. 이후 재결제 주문은 신청 id로 따라간다. */
    public void linkPayment(String paymentId) {
        if (this.paymentId == null) {
            this.paymentId = paymentId;
        }
    }

    public void complete(LocalDateTime now) {
        requirePending();
        this.status = PackageUpgradeStatus.COMPLETED;
        this.completedAt = now;
    }

    public void cancel(LocalDateTime now) {
        requirePending();
        this.status = PackageUpgradeStatus.CANCELED;
        this.canceledAt = now;
    }

    private void requirePending() {
        if (!isPending()) {
            throw new CustomException(ErrorCode.PACKAGE_UPGRADE_STATE_CONFLICT,
                    " 진행 중인 상향이 아닙니다. upgradeId=" + id + ", status=" + status);
        }
    }
}
