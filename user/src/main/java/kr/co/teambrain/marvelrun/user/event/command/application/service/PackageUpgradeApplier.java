package kr.co.teambrain.marvelrun.user.event.command.application.service;

import jakarta.persistence.EntityManager;
import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import kr.co.teambrain.marvelrun.common.discount.model.RegistrationPriceCalculationSnapshot;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ProductType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;
import kr.co.teambrain.marvelrun.common.inheritance_enum.upgrade.PackageUpgradeStatus;
import kr.co.teambrain.marvelrun.common.json_object.PackageUpgradeEntry;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityRequirementDiff;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.CapacityModificationService;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.ReservationHistoryRecorder;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.discount.service.RegistrationPriceCalculationRecorder;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationEntryDraft;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.PackageUpgrade;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationEntry;
import kr.co.teambrain.marvelrun.user.event.command.repository.PackageUpgradeCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationEntryCommandRepository;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.PaymentAllocation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 상향 결제가 승인되면 신청을 패키지로 바꾼다.
 *
 * 결제 승인 트랜잭션 안에서만 부른다. 상향 대기가 없는 결제는 아무것도 하지 않는다.
 * 2차 트랜잭션에서 실패하면 돈은 빠지고 결제가 UNKNOWN으로 남으므로,
 * 같은 검사를 1차 트랜잭션(Toss 호출 전)에서 먼저 한다.
 */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class PackageUpgradeApplier {

    private final EntityManager entityManager;
    private final PackageUpgradeCommandRepository upgradeRepository;
    private final RegistrationEntryCommandRepository entryRepository;
    private final PackageUpgradeCapacitySupport capacitySupport;
    private final CapacityModificationService capacityModificationService;
    private final ReservationHistoryRecorder historyRecorder;
    private final PackageUpgradePricing pricing;
    private final RegistrationPriceCalculationRecorder priceCalculations;

    /**
     * 1차 트랜잭션: Toss 승인 전에 상향을 적용할 수 있는지 본다.
     * 2차 트랜잭션에서 실패할 수 있는 조건(참가 항목, 정원 숫자, 가격 기록)을 여기서 먼저 막는다.
     */
    public void validateBeforeConfirm(Payment payment, List<PaymentAllocation> allocations) {
        pendingUpgrade(payment, allocations).ifPresent(upgrade -> {
            validateApplicable(upgrade, allocations.get(0));
            Registration registration = allocations.get(0).getRegistration();

            // 단품 신청은 참가 항목이 지금 단품 하나뿐이어야 패키지 하위 종목 나머지를 더해 완성된다.
            List<RegistrationEntry> entries = entryRepository.findByRegistration_Id(registration.getId());
            if (entries.size() != 1
                    || !Objects.equals(entries.get(0).getEventCategory().getId(), registration.getEventCategory().getId())) {
                throw stateConflict(upgrade, " 상향 전 참가 항목이 단품 하나가 아닙니다. entryCount=" + entries.size());
            }

            // 상향 대기 중 정원 연결이 바뀌었으면 승인 전에 막는다. 참가자는 상향을 취소하고 다시 요청한다.
            Reservation reservation = capacitySupport.lockConsumedReservation(registration);
            CapacityRequirementDiff diff = capacitySupport.packageDiff(registration, upgrade.getTargetCategory(), reservation);
            if (!PackageUpgradeCapacitySupport.increases(diff).equals(PackageUpgradeCapacitySupport.heldQuantities(upgrade))) {
                throw CustomException.withResponseMessage(ErrorCode.PACKAGE_UPGRADE_STATE_CONFLICT,
                        "상향을 기다리는 동안 패키지 구성이 바뀌었습니다. 상향을 취소한 뒤 다시 요청해주세요.");
            }
            capacitySupport.validatePreheldMove(registration.getEvent().getId(), diff, upgrade);

            // 승인 때 남길 가격 계산이 상향 뒤 계약금액과 같아야 기록이 실패하지 않는다.
            DiscountModels.PriceResult price = pricing.calculate(registration, upgrade.getTargetCategory());
            if (price.finalAmount().compareTo(registration.getContractAmount()) != 0) {
                throw stateConflict(upgrade, " 패키지 가격 계산이 상향 계약금액과 다릅니다. finalAmount=" + price.finalAmount());
            }
        });
    }

    /** 2차 트랜잭션: 납부액 반영 전에 종목·참가 항목·정원을 패키지로 바꾼다. */
    public void applyIfPending(Payment payment, List<PaymentAllocation> allocations, LocalDateTime now) {
        Optional<PackageUpgrade> found = pendingUpgrade(payment, allocations);
        if (found.isEmpty()) {
            return;
        }
        PackageUpgrade upgrade = found.get();
        Registration registration = allocations.get(0).getRegistration();
        validateApplicable(upgrade, allocations.get(0));

        String eventId = registration.getEvent().getId();
        EventCategory packageCategory = upgrade.getTargetCategory();

        // 요청 때 잡아 둔 held를 그대로 확정으로 옮긴다. 새로 확보하지 않으므로 Toss 승인 뒤 정원 부족으로 실패하지 않는다.
        // 원래 단품 CATEGORY처럼 줄어드는 행은 확정 수량에서 돌려준다.
        Reservation reservation = capacitySupport.lockConsumedReservation(registration);
        CapacityRequirementDiff diff = capacitySupport.packageDiff(registration, packageCategory, reservation);
        capacityModificationService.confirmPreheldReservationMove(
                eventId, diff, PackageUpgradeCapacitySupport.heldQuantities(upgrade), now);

        registration.applyPackageUpgrade(packageCategory);
        historyRecorder.completeReservationModificationSnapshot(reservation);
        // 패키지 가격(정가 − 단품 결제금액 = 차액)의 근거를 패키지 기준 가격 계산으로 남긴다.
        priceCalculations.recordRegistrationPriceCalculation(registration,
                pricing.calculate(registration, packageCategory),
                RegistrationPriceCalculationSnapshot.Reason.PACKAGE_UPGRADED);

        for (PackageUpgradeEntry added : upgrade.getAddedEntries()) {
            EventCategory component = entityManager.find(EventCategory.class, added.eventCategoryId());
            if (component == null || !Objects.equals(component.getParentCategoryId(), packageCategory.getId())) {
                throw stateConflict(upgrade, " 더할 참가 항목의 종목이 패키지 구성이 아닙니다.");
            }
            entryRepository.save(RegistrationEntry.create(registration, new RegistrationEntryDraft(
                    component, added.name(), added.phNum(), added.birth(), added.gender())));
        }

        upgrade.complete(now);
        entityManager.flush();
    }

    /** 개인 신청 한 건의 추가 결제이고 그 신청에 진행 중인 상향이 있을 때만 상향 결제로 본다. */
    private Optional<PackageUpgrade> pendingUpgrade(Payment payment, List<PaymentAllocation> allocations) {
        if (!payment.isRegistrationPayment()
                || payment.getPurpose() != PaymentPurpose.ADDITIONAL_PAYMENT
                || allocations.size() != 1
                || allocations.get(0).getRegistration() == null) {
            return Optional.empty();
        }
        return upgradeRepository.findByRegistrationForUpdate(
                allocations.get(0).getRegistration().getId(), PackageUpgradeStatus.PENDING);
    }

    private void validateApplicable(PackageUpgrade upgrade, PaymentAllocation allocation) {
        Registration registration = allocation.getRegistration();
        EventCategory current = registration.getEventCategory();
        // 요청 때 더한 차액만큼만 부족하고, 종목은 아직 그 패키지를 가리키는 단품이어야 한다.
        boolean valid = !registration.isSoftDeleted()
                && registration.getStatus() == RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED
                && registration.getContractAmount() != null
                && registration.getPaidAmount() != null
                && registration.getContractAmount().subtract(registration.getPaidAmount())
                        .compareTo(upgrade.getAmount()) == 0
                && allocation.getAllocatedAmount() != null
                && allocation.getAllocatedAmount().compareTo(upgrade.getAmount()) == 0
                && current.getProductType() == ProductType.SINGLE
                && Objects.equals(current.getParentCategoryId(), upgrade.getTargetCategory().getId());
        if (!valid) {
            throw stateConflict(upgrade, " 상향 결제를 적용할 신청 상태가 아닙니다.");
        }
    }

    private static CustomException stateConflict(PackageUpgrade upgrade, String diagnostic) {
        return new CustomException(ErrorCode.PACKAGE_UPGRADE_STATE_CONFLICT,
                diagnostic + " upgradeId=" + upgrade.getId());
    }
}
