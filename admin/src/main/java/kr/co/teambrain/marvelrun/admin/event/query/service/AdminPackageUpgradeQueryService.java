package kr.co.teambrain.marvelrun.admin.event.query.service;

import jakarta.persistence.EntityManager;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.PackageUpgrade;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Payment;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.command.repository.PackageUpgradeCommandRepository;
import kr.co.teambrain.marvelrun.admin.event.query.dto.PendingPackageUpgradeItem;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentPurpose;
import kr.co.teambrain.marvelrun.common.inheritance_enum.upgrade.PackageUpgradeStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 관리자 상향 대기 조회. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminPackageUpgradeQueryService {

    private final PackageUpgradeCommandRepository upgrades;
    private final EntityManager entityManager;

    public List<PendingPackageUpgradeItem> findPending(String eventId) {
        List<PackageUpgrade> pending = upgrades.findAllForAdminList(eventId, PackageUpgradeStatus.PENDING);
        if (pending.isEmpty()) {
            return List.of();
        }
        List<String> registrationIds = pending.stream().map(u -> u.getRegistration().getId()).toList();
        Map<String, Payment> lastPayments = entityManager.createQuery("""
                        select p from Payment p
                        where p.registration.id in :ids and p.purpose = :purpose
                        """, Payment.class)
                .setParameter("ids", registrationIds)
                .setParameter("purpose", PaymentPurpose.ADDITIONAL_PAYMENT)
                .getResultList().stream()
                .collect(Collectors.toMap(p -> p.getRegistration().getId(), Function.identity(),
                        (a, b) -> Comparator.comparing(Payment::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                                .compare(a, b) >= 0 ? a : b));

        return pending.stream().map(upgrade -> {
            Registration registration = upgrade.getRegistration();
            Payment last = lastPayments.get(registration.getId());
            return new PendingPackageUpgradeItem(
                    upgrade.getId(),
                    registration.getId(),
                    registration.getName(),
                    registration.getPhNum(),
                    registration.getEventCategory().getName(),
                    upgrade.getTargetCategory().getId(),
                    upgrade.getTargetCategory().getName(),
                    upgrade.getAmount(),
                    registration.getRegistrationDate(),
                    upgrade.getCreatedAt(),
                    last == null ? null : last.getId(),
                    last == null ? null : last.getOrderId(),
                    last == null ? null : last.getProcessStatus(),
                    upgrade.getAddedEntries()
            );
        }).toList();
    }
}
