package kr.co.teambrain.marvelrun.user.event.command.application.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import kr.co.teambrain.marvelrun.common.inheritance_enum.ProductType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationActionType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.upgrade.PackageUpgradeStatus;
import kr.co.teambrain.marvelrun.common.json_object.PackageUpgradeEntry;
import kr.co.teambrain.marvelrun.common.json_object.PackageUpgradeHeldCapacity;
import kr.co.teambrain.marvelrun.user.capacity.command.application.domain.Reservation;
import kr.co.teambrain.marvelrun.user.capacity.command.application.dto.CapacityRequirementDiff;
import kr.co.teambrain.marvelrun.user.capacity.command.application.service.RegistrationCapacityService;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationEntryDraft;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.PackageUpgrade;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationItem;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult.Order;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.PackageUpgradeRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.PackageUpgradeOrderResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.RegistrationEntryValidator;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.loader.RegistrationPolicyLoader;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategoryCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.PackageUpgradeCommandRepository;
import kr.co.teambrain.marvelrun.user.event.command.repository.RegistrationItemCommandRepository;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyModels.Action;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyModels.RegistrationActionPolicyInput;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyService;
import kr.co.teambrain.marvelrun.user.payment.command.application.AdditionalPaymentPreparationService;
import kr.co.teambrain.marvelrun.user.payment.command.application.domain.Payment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 로그인한 참가자의 단품 → 패키지 상향 요청·결제창 다시 열기·취소.
 *
 * 잠금 순서는 다른 개인 수정과 같다: 대회 → 결제 → 신청 → 상향 대기 → 예약 → 정원(Capacity ID 순).
 * 요청 때 계약금액을 패키지 가격으로 올려 기존 추가 결제 흐름으로 결제받고,
 * 종목·참가 항목·정원 확정은 결제 승인 때 PackageUpgradeApplier가 한다.
 */
@Service
@RequiredArgsConstructor
public class PackageUpgradeCommandService {

    private final ServerTimeProvider time;
    private final EntityManager entityManager;
    private final RegistrationCapacityService registrationCapacityService;
    private final RegistrationModificationPaymentGuard paymentGuard;
    private final RegistrationActionPolicyService actionPolicies;
    private final EventCategoryCommandRepository categoryRepository;
    private final RegistrationPolicyLoader policyLoader;
    private final RegistrationEntryValidator entryValidator;
    private final PackageUpgradeCommandRepository upgradeRepository;
    private final PackageUpgradeCapacitySupport capacitySupport;
    private final AdditionalPaymentPreparationService additionalPayments;
    private final PackageUpgradePricing pricing;
    private final RegistrationItemCommandRepository itemRepository;

    /** 상향을 요청하고 차액 결제창 정보를 돌려준다. */
    @Transactional
    public PackageUpgradeOrderResponse request(String registrationId, String ordererKey, PackageUpgradeRequest request) {
        LocalDateTime now = time.currentDateTime();
        Locked locked = lock(registrationId, ordererKey);
        Registration registration = locked.registration();
        Event event = registration.getEvent();

        paymentGuard.validateLockedPayments(locked.payments());
        if (upgradeRepository.findByRegistrationForUpdate(registrationId, PackageUpgradeStatus.PENDING).isPresent()) {
            throw new CustomException(ErrorCode.PACKAGE_UPGRADE_IN_PROGRESS);
        }
        if (registration.getStatus() != RegistrationStatus.CONFIRMED
                || registration.getContractAmount() == null
                || registration.getPaidAmount() == null
                || registration.getContractAmount().compareTo(registration.getPaidAmount()) != 0) {
            throw CustomException.withResponseMessage(ErrorCode.PACKAGE_UPGRADE_NOT_ALLOWED,
                    "결제를 마친 신청만 패키지로 상향할 수 있습니다.");
        }

        EventCategory current = registration.getEventCategory();
        if (current.getProductType() != ProductType.SINGLE || current.getParentCategoryId() == null) {
            throw CustomException.withResponseMessage(ErrorCode.PACKAGE_UPGRADE_NOT_ALLOWED,
                    "단품 신청만 패키지로 상향할 수 있습니다.");
        }
        // 상향은 구매 줄이 단품 하나뿐인 신청만 한다. 줄을 골라 상향하는 흐름은 아직 없다.
        RegistrationItem item = singleItem(registration);
        EventCategory packageCategory = categoryRepository.findById(current.getParentCategoryId())
                .filter(found -> Objects.equals(found.getEvent().getId(), event.getId()))
                .filter(found -> found.getProductType() == ProductType.PACKAGE)
                .orElseThrow(() -> new CustomException(ErrorCode.REGISTRATION_POLICY_CONFIGURATION_ERROR,
                        " 단품이 가리키는 패키지가 없습니다. categoryId=" + current.getId()));
        if (!Boolean.TRUE.equals(packageCategory.getIsActive())) {
            throw CustomException.withResponseMessage(ErrorCode.PACKAGE_UPGRADE_NOT_ALLOWED,
                    "지금은 패키지를 판매하지 않습니다.");
        }

        // 신청일이 속한 차수의 마감 전에만 상향한다. 수정과 결제를 모두 허용해야 한다.
        List<RegistrationActionPolicyInput> policies = actionPolicies.loadEnabledRegistrationActionPolicies(
                event.getId(), List.of(RegistrationActionType.MODIFY, RegistrationActionType.PAYMENT));
        actionPolicies.validateRegistrationActionPolicy(event, registration, Action.MODIFY, now, policies);
        actionPolicies.validateRegistrationActionPolicy(event, registration, Action.PAYMENT, now, policies);

        List<EventCategory> components = categoryRepository
                .findByEvent_IdAndParentCategoryIdOrderByOrderAscIdAsc(event.getId(), packageCategory.getId());
        List<RegistrationEntryDraft> drafts = entryValidator.validateUpgrade(
                event,
                packageCategory,
                current,
                components,
                request.entries(),
                registration.getGuardianName(),
                registration.isGuardianConsent(),
                policyLoader.loadEventPolicy(event.getId()),
                policyLoader.loadCategoryPolicies(event.getId(),
                        components.stream().map(EventCategory::getId).collect(Collectors.toSet())),
                now.toLocalDate()
        );

        // 차액은 신청 때와 같은 가격 계산으로 정한 패키지 가격에서 지금 계약금액(= 결제금액)을 뺀 값이다.
        DiscountModels.PriceResult price = pricing.calculate(registration, packageCategory);
        BigDecimal amount = price.finalAmount().subtract(registration.getContractAmount());
        if (amount.signum() <= 0) {
            throw new CustomException(ErrorCode.REGISTRATION_POLICY_CONFIGURATION_ERROR,
                    " 패키지 가격이 지금 계약금액보다 크지 않습니다. registrationId=" + registrationId);
        }

        // 앞서 만든 READY 주문은 쓰지 않는다. 진행 중인 결제·환불이 있으면 위에서 이미 막혔다.
        paymentGuard.invalidateLockedReadyPayments(locked.payments());

        Reservation reservation = capacitySupport.lockConsumedReservation(registration);
        CapacityRequirementDiff diff = capacitySupport.packageDiff(item, packageCategory, components.size(), reservation);
        List<PackageUpgradeHeldCapacity> held = capacitySupport.holdIncreases(event.getId(), diff, now);

        capacitySupport.recordAmountChange(reservation, now, "패키지 상향 요청: 계약금액에 차액 추가",
                () -> registration.startPackageUpgrade(amount));
        PackageUpgrade upgrade = upgradeRepository.save(PackageUpgrade.create(
                registration,
                packageCategory,
                amount,
                drafts.stream().map(PackageUpgradeCommandService::toEntry).toList(),
                held
        ));
        entityManager.flush();

        Order order = additionalPayments.prepareLockedPackageUpgrade(locked.payments(), registration, packageCategory, now);
        upgrade.linkPayment(order.paymentId());
        return PackageUpgradeOrderResponse.of(upgrade.getId(), packageCategory.getId(), packageCategory.getName(), order);
    }

    /** 결제창을 다시 연다. 같은 금액의 READY 주문이 있으면 그 주문을 그대로 쓴다. */
    @Transactional
    public PackageUpgradeOrderResponse preparePayment(String registrationId, String ordererKey) {
        LocalDateTime now = time.currentDateTime();
        Locked locked = lock(registrationId, ordererKey);
        PackageUpgrade upgrade = upgradeRepository
                .findByRegistrationForUpdate(registrationId, PackageUpgradeStatus.PENDING)
                .orElseThrow(() -> new CustomException(ErrorCode.PACKAGE_UPGRADE_NOT_FOUND));
        Order order = additionalPayments.prepareLockedPackageUpgrade(locked.payments(), locked.registration(),
                upgrade.getTargetCategory(), now);
        upgrade.linkPayment(order.paymentId());
        return PackageUpgradeOrderResponse.of(upgrade.getId(), upgrade.getTargetCategory().getId(),
                upgrade.getTargetCategory().getName(), order);
    }

    /** 결제하지 않은 상향을 취소한다. 기간 정책은 보지 않는다(마감 뒤에도 스스로 풀 수 있다). */
    @Transactional
    public void cancel(String registrationId, String ordererKey) {
        LocalDateTime now = time.currentDateTime();
        Locked locked = lock(registrationId, ordererKey);
        PackageUpgrade upgrade = upgradeRepository
                .findByRegistrationForUpdate(registrationId, PackageUpgradeStatus.PENDING)
                .orElseThrow(() -> new CustomException(ErrorCode.PACKAGE_UPGRADE_NOT_FOUND));

        // 승인 중이거나 결과를 모르는 상향 결제가 있으면 막고, READY 주문은 무효로 한다.
        paymentGuard.prepareLockedPayments(locked.payments());
        Reservation reservation = capacitySupport.lockConsumedReservation(locked.registration());
        capacitySupport.releaseHeld(upgrade, now);
        capacitySupport.recordAmountChange(reservation, now, "패키지 상향 취소: 계약금액에서 차액 제외",
                () -> locked.registration().cancelPackageUpgrade(upgrade.getAmount()));
        upgrade.cancel(now);
        entityManager.flush();
    }

    /** 신청의 구매 줄이 하나뿐이고 그 줄이 신청 행의 단품과 같을 때 그 줄을 돌려준다. */
    private RegistrationItem singleItem(Registration registration) {
        List<RegistrationItem> items = itemRepository.findByRegistration_IdOrderBySortOrderAsc(registration.getId());
        if (items.size() > 1) {
            throw new CustomException(ErrorCode.PACKAGE_UPGRADE_MULTI_ITEM_NOT_ALLOWED,
                    " registrationId=" + registration.getId() + ", itemCount=" + items.size());
        }
        if (items.isEmpty()
                || !Objects.equals(items.get(0).getEventCategory().getId(), registration.getEventCategory().getId())) {
            throw new CustomException(ErrorCode.PACKAGE_UPGRADE_STATE_CONFLICT,
                    " 상향할 단품 구매 줄이 신청 종목과 맞지 않습니다. registrationId=" + registration.getId());
        }
        return items.get(0);
    }

    /** 대회 → 결제 → 신청 순으로 잠그고, 다시 읽은 신청이 토큰 주문자의 살아 있는 개인 주문인지 본다. */
    private Locked lock(String registrationId, String ordererKey) {
        Registration found = entityManager.find(Registration.class, registrationId);
        if (found == null) {
            throw new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED);
        }
        String eventId = found.getEvent().getId();
        registrationCapacityService.lockEvent(eventId);
        List<Payment> payments = paymentGuard.lockPersonal(eventId, registrationId);
        entityManager.refresh(found, LockModeType.PESSIMISTIC_WRITE);
        if (found.isSoftDeleted() || !found.isOwnedBy(ordererKey)) {
            throw new CustomException(ErrorCode.REGISTRATION_ACCESS_DENIED);
        }
        found.validateOnlineRegistrationProcessingAllowed();
        return new Locked(found, payments);
    }

    private static PackageUpgradeEntry toEntry(RegistrationEntryDraft draft) {
        return new PackageUpgradeEntry(draft.eventCategory().getId(), draft.name(), draft.phNum(),
                draft.birth(), draft.gender());
    }

    private record Locked(Registration registration, List<Payment> payments) {
    }
}
