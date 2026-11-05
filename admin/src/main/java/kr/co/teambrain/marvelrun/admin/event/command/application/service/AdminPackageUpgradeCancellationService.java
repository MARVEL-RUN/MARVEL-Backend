package kr.co.teambrain.marvelrun.admin.event.command.application.service;

import kr.co.teambrain.marvelrun.admin.capacity.command.repository.CapacityCommandRepository;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.PackageUpgrade;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.PackageUpgradeCancellationResult;
import kr.co.teambrain.marvelrun.admin.event.command.repository.PackageUpgradeCommandRepository;
import kr.co.teambrain.marvelrun.admin.payment.command.AdminRefundLockRepository;
import kr.co.teambrain.marvelrun.admin.payment.command.AdminRefundLockedScope.PaymentRow;
import kr.co.teambrain.marvelrun.admin.payment.command.AdminRefundPreparationStore;
import kr.co.teambrain.marvelrun.common.json_object.PackageUpgradeHeldCapacity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자가 결제하지 않은 상향 대기를 취소한다.
 *
 * 사용자 앱 상향 흐름과 같은 순서로 잠근다: 대회 → 결제 → 신청 → 상향 대기 → 정원.
 * READY 상향 주문은 무효로 하고, 잡아 둔 held를 돌려주고, 계약금액을 단품 가격으로 되돌린다.
 * 승인 중이거나 결과를 모르는 결제가 있으면 건드리지 않는다(Toss 결과를 먼저 확인해야 한다).
 */
@Service
@RequiredArgsConstructor
public class AdminPackageUpgradeCancellationService {

    private final AdminRefundLockRepository locks;
    private final AdminRefundPreparationStore store;
    private final CapacityCommandRepository capacities;
    private final PackageUpgradeCommandRepository upgrades;
    private final ServerTimeProvider time;

    /** 단건 취소 API. */
    @Transactional
    public PackageUpgradeCancellationResult cancel(String eventId, String upgradeId) {
        return perform(eventId, upgradeId, time.currentDateTime());
    }

    /** 일괄 취소의 한 건. 실패해도 다른 건에 영향이 없도록 따로 커밋한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PackageUpgradeCancellationResult cancelInNewTransaction(String eventId, String upgradeId, LocalDateTime now) {
        return perform(eventId, upgradeId, now);
    }

    private PackageUpgradeCancellationResult perform(String eventId, String upgradeId, LocalDateTime now) {
        // 첫 조회는 잠글 범위를 알기 위한 것이다. 판단은 잠근 뒤 다시 읽은 값으로 한다.
        PackageUpgrade observed = upgrades.findById(upgradeId)
                .filter(found -> eventId.equals(found.getEvent().getId()))
                .orElseThrow(() -> new CustomException(ErrorCode.PACKAGE_UPGRADE_NOT_FOUND));
        String registrationId = observed.getRegistration().getId();

        if (!locks.lockEvent(eventId)) {
            throw new CustomException(ErrorCode.EVENT_NOT_FOUND);
        }
        List<PaymentRow> payments = locks.lockPayments(eventId, null, registrationId);
        for (PaymentRow payment : payments) {
            if (payment.status() == null) {
                throw new CustomException(ErrorCode.PAYMENT_CANCEL_INTEGRITY_ERROR);
            }
            switch (payment.status()) {
                case READY, FAILED, INVALIDATED, COMPLETED -> { }
                default -> throw new CustomException(ErrorCode.PACKAGE_UPGRADE_PAYMENT_IN_PROGRESS,
                        " paymentId=" + payment.id());
            }
        }
        if (locks.lockRegistrations(eventId, List.of(registrationId)).size() != 1) {
            throw new CustomException(ErrorCode.REGISTRATION_NOT_FOUND);
        }
        Registration registration = store.current(Registration.class, registrationId);
        PackageUpgrade upgrade = store.current(PackageUpgrade.class, upgradeId);
        if (!upgrade.isPending()) {
            return PackageUpgradeCancellationResult.skipped(upgrade, registration, "이미 끝난 상향입니다. status=" + upgrade.getStatus());
        }

        for (PaymentRow row : payments) {
            store.current(Payment.class, row.id()).invalidateForRegistrationModification();
        }
        for (PackageUpgradeHeldCapacity held : upgrade.getHeldCapacities()) {
            if (capacities.releaseHeld(eventId, held.capacityId(), held.quantity(), now) != 1) {
                throw new CustomException(ErrorCode.CAPACITY_COUNTER_MISMATCH,
                        " 상향 held 반환 실패. upgradeId=" + upgradeId + ", capacityId=" + held.capacityId());
            }
        }
        registration.cancelPackageUpgradeByAdmin(upgrade.getAmount());
        upgrade.cancelByAdmin(now);
        store.flush();
        return PackageUpgradeCancellationResult.canceled(upgrade, registration);
    }
}
