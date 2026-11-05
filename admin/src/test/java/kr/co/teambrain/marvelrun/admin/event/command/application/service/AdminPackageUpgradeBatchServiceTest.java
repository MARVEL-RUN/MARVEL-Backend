package kr.co.teambrain.marvelrun.admin.event.command.application.service;

import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.RegistrationActionPolicy;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.PackageUpgradeBatchCancellationRequest;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.PackageUpgradeBatchCancellationResponse;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.PackageUpgradeCancellationResult;
import kr.co.teambrain.marvelrun.admin.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.admin.event.command.repository.PackageUpgradeCommandRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationActionPolicyRepository;
import kr.co.teambrain.marvelrun.common.inheritance_enum.EventFormat;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationActionType;
import kr.co.teambrain.marvelrun.common.inheritance_enum.upgrade.PackageUpgradeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 남은 상향 대기 일괄 취소가 차수 마감 뒤에만 돌고, 건별 실패를 모아 돌려주는지 본다. */
@ExtendWith(MockitoExtension.class)
class AdminPackageUpgradeBatchServiceTest {

    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 11, 20, 17, 0);

    @Mock private EventCommandRepository events;
    @Mock private RegistrationActionPolicyRepository actionPolicies;
    @Mock private PackageUpgradeCommandRepository upgrades;
    @Mock private AdminPackageUpgradeCancellationService cancellation;
    @Mock private ServerTimeProvider time;
    @Mock private Event event;

    private AdminPackageUpgradeBatchService service() {
        return new AdminPackageUpgradeBatchService(events, actionPolicies, upgrades, cancellation, time);
    }

    /** 버추얼런이 아닌 대회는 거절한다. */
    @Test
    void rejectsOfflineEvent() {
        when(time.currentDateTime()).thenReturn(DEADLINE.plusHours(1));
        when(events.findById("event")).thenReturn(Optional.of(event));
        when(event.getEventFormat()).thenReturn(EventFormat.OFFLINE);

        expectInvalid(() -> service().cancelPendingBeforeDeadline("event", new PackageUpgradeBatchCancellationRequest(DEADLINE)));
    }

    /** 켜져 있는 PAYMENT 정책의 마감 시각이 아니거나, 마감 전이면 거절한다. */
    @Test
    void rejectsUnknownDeadlineOrBeforeDeadline() {
        when(events.findById("event")).thenReturn(Optional.of(event));
        when(event.getEventFormat()).thenReturn(EventFormat.VIRTUAL);
        when(actionPolicies.findAllByEventIdAndActionTypeAndEnabledTrueOrderByRegistrationStartAtAscIdAsc(
                "event", RegistrationActionType.PAYMENT)).thenReturn(List.of(policy(DEADLINE)));
        when(time.currentDateTime()).thenReturn(DEADLINE.plusHours(1), DEADLINE.minusMinutes(1));

        expectInvalid(() -> service().cancelPendingBeforeDeadline("event",
                new PackageUpgradeBatchCancellationRequest(DEADLINE.plusDays(1))));
        expectInvalid(() -> service().cancelPendingBeforeDeadline("event", new PackageUpgradeBatchCancellationRequest(DEADLINE)));
        verifyNoInteractions(cancellation);
    }

    /** 건마다 따로 취소하고, 실패한 건은 결과에 남긴 채 다음 건으로 넘어간다. */
    @Test
    void cancelsEachAndCollectsFailures() {
        LocalDateTime now = DEADLINE.plusHours(1);
        when(time.currentDateTime()).thenReturn(now);
        when(events.findById("event")).thenReturn(Optional.of(event));
        when(event.getEventFormat()).thenReturn(EventFormat.VIRTUAL);
        when(actionPolicies.findAllByEventIdAndActionTypeAndEnabledTrueOrderByRegistrationStartAtAscIdAsc(
                "event", RegistrationActionType.PAYMENT)).thenReturn(List.of(policy(DEADLINE)));
        when(upgrades.findCancelableIdsBefore(eq("event"), eq(PackageUpgradeStatus.PENDING), eq(DEADLINE),
                eq(AdminPackageUpgradeBatchService.BLOCKED), any()))
                .thenReturn(List.of("u1", "u2"));
        when(cancellation.cancelInNewTransaction("event", "u1", now))
                .thenReturn(new PackageUpgradeCancellationResult("u1", "r1", "이름", true, "ok"));
        when(cancellation.cancelInNewTransaction("event", "u2", now))
                .thenThrow(new CustomException(ErrorCode.PACKAGE_UPGRADE_PAYMENT_IN_PROGRESS));
        when(upgrades.countCancelableBefore("event", PackageUpgradeStatus.PENDING, DEADLINE,
                AdminPackageUpgradeBatchService.BLOCKED)).thenReturn(1L);
        when(upgrades.countBlockedBefore("event", PackageUpgradeStatus.PENDING, DEADLINE,
                AdminPackageUpgradeBatchService.BLOCKED)).thenReturn(2L);

        PackageUpgradeBatchCancellationResponse response =
                service().cancelPendingBeforeDeadline("event", new PackageUpgradeBatchCancellationRequest(DEADLINE));

        assertThat(response.processedCount()).isEqualTo(2);
        assertThat(response.canceledCount()).isEqualTo(1);
        assertThat(response.failedCount()).isEqualTo(1);
        assertThat(response.remainingCount()).isEqualTo(1);
        assertThat(response.blockedCount()).isEqualTo(2);
        assertThat(response.results().get(1).message())
                .isEqualTo(ErrorCode.PACKAGE_UPGRADE_PAYMENT_IN_PROGRESS.getMessage());
    }

    private static RegistrationActionPolicy policy(LocalDateTime effectiveFrom) {
        return RegistrationActionPolicy.builder().id("p").eventId("event").actionType(RegistrationActionType.PAYMENT)
                .registrationStartAt(effectiveFrom.minusDays(20)).registrationEndAt(effectiveFrom)
                .effectiveFrom(effectiveFrom).enabled(true).build();
    }

    private static void expectInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PACKAGE_UPGRADE_CANCELLATION_REQUEST);
    }
}
