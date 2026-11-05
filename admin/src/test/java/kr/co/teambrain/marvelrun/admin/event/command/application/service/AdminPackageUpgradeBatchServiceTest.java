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
import kr.co.teambrain.marvelrun.admin.event.policy.RegistrationActionPolicyModels.Action;
import kr.co.teambrain.marvelrun.admin.event.policy.RegistrationActionPolicyModels.RegistrationActionPolicyInput;
import kr.co.teambrain.marvelrun.admin.event.policy.VirtualRounds;
import kr.co.teambrain.marvelrun.admin.event.query.support.RegistrationActionPolicyReader;
import kr.co.teambrain.marvelrun.common.inheritance_enum.EventFormat;
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
    @Mock private RegistrationActionPolicyReader policyReader;
    @Mock private PackageUpgradeCommandRepository upgrades;
    @Mock private AdminPackageUpgradeCancellationService cancellation;
    @Mock private ServerTimeProvider time;
    @Mock private Event event;

    private AdminPackageUpgradeBatchService service() {
        return new AdminPackageUpgradeBatchService(events, policyReader, upgrades, cancellation, time);
    }

    /** 버추얼런이 아닌 대회는 거절한다. */
    @Test
    void rejectsOfflineEvent() {
        when(time.currentDateTime()).thenReturn(DEADLINE.plusHours(1));
        when(events.findById("event")).thenReturn(Optional.of(event));
        when(event.getEventFormat()).thenReturn(EventFormat.OFFLINE);

        expectInvalid(() -> service().cancelPendingBeforeDeadline("event", new PackageUpgradeBatchCancellationRequest(DEADLINE, null)));
    }

    /** 차수 마감 시각이 아니면 VIRTUAL_ROUND_NOT_FOUND, 마감 전이면 잘못된 요청으로 거절한다. */
    @Test
    void rejectsUnknownDeadlineOrBeforeDeadline() {
        when(events.findById("event")).thenReturn(Optional.of(event));
        when(event.getEventFormat()).thenReturn(EventFormat.VIRTUAL);
        when(policyReader.loadEnabledRegistrationActionPolicies("event", VirtualRounds.ROUND_ACTION_TYPES))
                .thenReturn(round(DEADLINE));
        when(time.currentDateTime()).thenReturn(DEADLINE.plusHours(1), DEADLINE.minusMinutes(1));

        assertThatThrownBy(() -> service().cancelPendingBeforeDeadline("event",
                new PackageUpgradeBatchCancellationRequest(DEADLINE.plusDays(1), null)))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VIRTUAL_ROUND_NOT_FOUND));
        expectInvalid(() -> service().cancelPendingBeforeDeadline("event", new PackageUpgradeBatchCancellationRequest(DEADLINE, null)));
        verifyNoInteractions(cancellation);
    }

    /** 건마다 따로 취소하고, 실패한 건은 결과에 남긴 채 다음 건으로 넘어간다. 커서는 마지막으로 처리한 id다. */
    @Test
    void cancelsEachAndCollectsFailures() {
        LocalDateTime now = DEADLINE.plusHours(1);
        when(time.currentDateTime()).thenReturn(now);
        when(events.findById("event")).thenReturn(Optional.of(event));
        when(event.getEventFormat()).thenReturn(EventFormat.VIRTUAL);
        when(policyReader.loadEnabledRegistrationActionPolicies("event", VirtualRounds.ROUND_ACTION_TYPES))
                .thenReturn(round(DEADLINE));
        when(upgrades.findCancelableIdsBefore(eq("event"), eq(PackageUpgradeStatus.PENDING), eq(DEADLINE),
                eq(""), eq(AdminPackageUpgradeBatchService.BLOCKED), any()))
                .thenReturn(List.of("u1", "u2"));
        when(cancellation.cancelInNewTransaction("event", "u1", now))
                .thenReturn(new PackageUpgradeCancellationResult("u1", "r1", "이름", true, "ok"));
        when(cancellation.cancelInNewTransaction("event", "u2", now))
                .thenThrow(new CustomException(ErrorCode.PACKAGE_UPGRADE_PAYMENT_IN_PROGRESS));
        when(upgrades.countCancelableBefore("event", PackageUpgradeStatus.PENDING, DEADLINE, "u2",
                AdminPackageUpgradeBatchService.BLOCKED)).thenReturn(1L);
        when(upgrades.countBlockedBefore("event", PackageUpgradeStatus.PENDING, DEADLINE,
                AdminPackageUpgradeBatchService.BLOCKED)).thenReturn(2L);

        PackageUpgradeBatchCancellationResponse response =
                service().cancelPendingBeforeDeadline("event", new PackageUpgradeBatchCancellationRequest(DEADLINE, null));

        assertThat(response.processedCount()).isEqualTo(2);
        assertThat(response.canceledCount()).isEqualTo(1);
        assertThat(response.failedCount()).isEqualTo(1);
        assertThat(response.remainingCount()).isEqualTo(1);
        assertThat(response.blockedCount()).isEqualTo(2);
        assertThat(response.nextAfterUpgradeId()).isEqualTo("u2");
        assertThat(response.results().get(1).message())
                .isEqualTo(ErrorCode.PACKAGE_UPGRADE_PAYMENT_IN_PROGRESS.getMessage());
    }

    /** 앞 응답의 커서 뒤부터 고르므로 계속 실패하는 건을 다시 고르지 않는다. 남은 건이 없으면 커서는 null이다. */
    @Test
    void continuesAfterCursorAndEndsWithNullCursor() {
        LocalDateTime now = DEADLINE.plusHours(1);
        when(time.currentDateTime()).thenReturn(now);
        when(events.findById("event")).thenReturn(Optional.of(event));
        when(event.getEventFormat()).thenReturn(EventFormat.VIRTUAL);
        when(policyReader.loadEnabledRegistrationActionPolicies("event", VirtualRounds.ROUND_ACTION_TYPES))
                .thenReturn(round(DEADLINE));
        when(upgrades.findCancelableIdsBefore(eq("event"), eq(PackageUpgradeStatus.PENDING), eq(DEADLINE),
                eq("u2"), eq(AdminPackageUpgradeBatchService.BLOCKED), any()))
                .thenReturn(List.of("u3"));
        when(cancellation.cancelInNewTransaction("event", "u3", now))
                .thenReturn(new PackageUpgradeCancellationResult("u3", "r3", "이름", true, "ok"));
        when(upgrades.countCancelableBefore("event", PackageUpgradeStatus.PENDING, DEADLINE, "u3",
                AdminPackageUpgradeBatchService.BLOCKED)).thenReturn(0L);

        PackageUpgradeBatchCancellationResponse response =
                service().cancelPendingBeforeDeadline("event", new PackageUpgradeBatchCancellationRequest(DEADLINE, "u2"));

        assertThat(response.processedCount()).isEqualTo(1);
        assertThat(response.remainingCount()).isZero();
        assertThat(response.nextAfterUpgradeId()).isNull();
        verify(cancellation, never()).cancelInNewTransaction("event", "u2", now);
    }

    /** 마감 시각이 deadline인 차수 하나의 MODIFY·PAYMENT·REFUND 정책이다. */
    private static List<RegistrationActionPolicyInput> round(LocalDateTime deadline) {
        return List.of(Action.MODIFY, Action.PAYMENT, Action.REFUND).stream()
                .map(action -> new RegistrationActionPolicyInput("p", "event", action,
                        deadline.minusDays(20), deadline, deadline, true))
                .toList();
    }

    private static void expectInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_PACKAGE_UPGRADE_CANCELLATION_REQUEST);
    }
}
