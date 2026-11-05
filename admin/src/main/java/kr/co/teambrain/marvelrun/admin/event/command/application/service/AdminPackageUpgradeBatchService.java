package kr.co.teambrain.marvelrun.admin.event.command.application.service;

import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.PackageUpgradeBatchCancellationRequest;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.PackageUpgradeBatchCancellationResponse;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.PackageUpgradeCancellationResult;
import kr.co.teambrain.marvelrun.admin.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.admin.event.command.repository.PackageUpgradeCommandRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationActionPolicyRepository;
import kr.co.teambrain.marvelrun.common.inheritance_enum.EventFormat;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationActionType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.PaymentProcessStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.upgrade.PackageUpgradeStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 차수 마감 뒤 배번 발급 전에 남은 상향 대기를 일괄 취소한다.
 *
 * 대회 행을 배치 내내 잠그지 않도록 건마다 따로 트랜잭션을 연다. 한 번에 최대 50건이다.
 * 승인 중·결과 미확인 결제가 걸린 건은 실패로 남기고 건너뛴다(Toss 결과 확인 뒤 다시 부른다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminPackageUpgradeBatchService {

    static final int BATCH_SIZE = 50;

    /** 이 상태의 결제가 걸린 상향은 Toss 결과를 확인하기 전까지 일괄 취소에서 뺀다. */
    static final List<PaymentProcessStatus> BLOCKED =
            List.of(PaymentProcessStatus.CONFIRMING, PaymentProcessStatus.UNKNOWN);

    private final EventCommandRepository events;
    private final RegistrationActionPolicyRepository actionPolicies;
    private final PackageUpgradeCommandRepository upgrades;
    private final AdminPackageUpgradeCancellationService cancellation;
    private final ServerTimeProvider time;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PackageUpgradeBatchCancellationResponse cancelPendingBeforeDeadline(
            String eventId,
            PackageUpgradeBatchCancellationRequest request
    ) {
        LocalDateTime now = time.currentDateTime();
        LocalDateTime deadline = request == null ? null : request.deadline();
        validate(eventId, deadline, now);

        List<String> ids = upgrades.findCancelableIdsBefore(
                eventId, PackageUpgradeStatus.PENDING, deadline, BLOCKED, PageRequest.of(0, BATCH_SIZE));
        List<PackageUpgradeCancellationResult> results = new ArrayList<>();
        int canceled = 0;
        for (String upgradeId : ids) {
            try {
                PackageUpgradeCancellationResult result = cancellation.cancelInNewTransaction(eventId, upgradeId, now);
                results.add(result);
                if (result.canceled()) {
                    canceled++;
                }
            } catch (CustomException exception) {
                results.add(PackageUpgradeCancellationResult.failed(upgradeId, exception.getErrorCode().getMessage()));
            } catch (RuntimeException exception) {
                log.error("상향 대기 일괄 취소 오류: eventId={}, upgradeId={}, exceptionType={}",
                        eventId, upgradeId, exception.getClass().getName());
                results.add(PackageUpgradeCancellationResult.failed(upgradeId, ErrorCode.INTERNAL_SERVER_ERROR.getMessage()));
            }
        }
        long remaining = upgrades.countCancelableBefore(eventId, PackageUpgradeStatus.PENDING, deadline, BLOCKED);
        long blocked = upgrades.countBlockedBefore(eventId, PackageUpgradeStatus.PENDING, deadline, BLOCKED);
        int failed = (int) results.stream().filter(r -> !r.canceled()).count();
        return new PackageUpgradeBatchCancellationResponse(deadline, results.size(), canceled, failed, remaining,
                blocked, results);
    }

    /** 버추얼런 대회이고, 마감 시각이 실제 차수 마감이며, 그 시각이 이미 지났을 때만 돌린다. */
    private void validate(String eventId, LocalDateTime deadline, LocalDateTime now) {
        if (eventId == null || eventId.isBlank() || deadline == null) {
            throw new CustomException(ErrorCode.INVALID_PACKAGE_UPGRADE_CANCELLATION_REQUEST);
        }
        Event event = events.findById(eventId)
                .orElseThrow(() -> new CustomException(ErrorCode.EVENT_NOT_FOUND));
        if (event.getEventFormat() != EventFormat.VIRTUAL) {
            throw new CustomException(ErrorCode.INVALID_PACKAGE_UPGRADE_CANCELLATION_REQUEST, " 버추얼런 대회가 아닙니다.");
        }
        boolean roundDeadline = actionPolicies
                .findAllByEventIdAndActionTypeAndEnabledTrueOrderByRegistrationStartAtAscIdAsc(
                        eventId, RegistrationActionType.PAYMENT)
                .stream()
                .anyMatch(policy -> deadline.equals(policy.getEffectiveFrom()));
        if (!roundDeadline) {
            throw new CustomException(ErrorCode.INVALID_PACKAGE_UPGRADE_CANCELLATION_REQUEST,
                    " 켜져 있는 PAYMENT 정책의 마감 시각이 아닙니다. deadline=" + deadline);
        }
        if (now.isBefore(deadline)) {
            throw new CustomException(ErrorCode.INVALID_PACKAGE_UPGRADE_CANCELLATION_REQUEST,
                    " 차수 마감 전에는 일괄 취소하지 않습니다. deadline=" + deadline);
        }
    }
}
