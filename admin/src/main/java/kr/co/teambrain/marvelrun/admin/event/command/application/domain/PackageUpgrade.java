package kr.co.teambrain.marvelrun.admin.event.command.application.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.common.entity.PackageUpgradeBase;
import kr.co.teambrain.marvelrun.common.inheritance_enum.upgrade.PackageUpgradeStatus;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/** 단품 → 패키지 상향 대기. 관리자 앱은 조회하고, 결제하지 않은 상향을 취소한다. */
@Getter
@Entity
@SuperBuilder
@Table(name = "package_upgrade")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PackageUpgrade extends PackageUpgradeBase<Event, Registration, EventCategory> {

    public void cancelByAdmin(LocalDateTime now) {
        if (!isPending()) {
            throw new CustomException(ErrorCode.PACKAGE_UPGRADE_STATE_CONFLICT,
                    " 진행 중인 상향이 아닙니다. upgradeId=" + id + ", status=" + status);
        }
        this.status = PackageUpgradeStatus.CANCELED;
        this.canceledAt = now;
    }
}
