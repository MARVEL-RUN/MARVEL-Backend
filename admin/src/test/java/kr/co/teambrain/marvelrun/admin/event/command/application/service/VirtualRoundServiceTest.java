package kr.co.teambrain.marvelrun.admin.event.command.application.service;

import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.RegistrationActionPolicy;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.VirtualRoundsRequest;
import kr.co.teambrain.marvelrun.admin.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.admin.event.command.repository.RegistrationActionPolicyCommandRepository;
import kr.co.teambrain.marvelrun.admin.event.policy.RegistrationActionPolicyModels.Action;
import kr.co.teambrain.marvelrun.admin.event.policy.RegistrationActionPolicyModels.RegistrationActionPolicyInput;
import kr.co.teambrain.marvelrun.admin.event.query.support.RegistrationActionPolicyReader;
import kr.co.teambrain.marvelrun.common.inheritance_enum.EventFormat;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationActionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 차수 교체의 검증 오류, 마감 차수 보존, 생성되는 정책 행을 확인한다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VirtualRoundServiceTest {
    private static final String EVENT_ID = "e1";
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 11, 1, 0, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 11, 20, 17, 0);
    private static final LocalDateTime T3 = LocalDateTime.of(2026, 11, 23, 0, 0);
    private static final LocalDateTime T4 = LocalDateTime.of(2026, 11, 27, 17, 0);
    private static final LocalDateTime END = LocalDateTime.of(2027, 1, 19, 23, 59, 59);

    @Mock ServerTimeProvider serverTimeProvider;
    @Mock EventCommandRepository eventRepository;
    @Mock RegistrationActionPolicyReader policyReader;
    @Mock RegistrationActionPolicyCommandRepository policyRepository;

    private VirtualRoundService service;
    private final Event event = mock(Event.class);

    @BeforeEach
    void setUp() {
        service = new VirtualRoundService(serverTimeProvider, eventRepository, policyReader, policyRepository);
        when(serverTimeProvider.currentDateTime()).thenReturn(LocalDateTime.of(2026, 11, 5, 10, 0));
        when(eventRepository.findByIdForUpdate(EVENT_ID)).thenReturn(java.util.Optional.of(event));
        when(event.getEventFormat()).thenReturn(EventFormat.VIRTUAL);
        when(event.getRegistStartDate()).thenReturn(T1);
        when(event.getRegistDeadline()).thenReturn(END);
        when(policyReader.loadEnabledRegistrationActionPolicies(eq(EVENT_ID), any(java.util.Collection.class))).thenReturn(List.of());
        when(policyRepository.findAllByEventIdAndActionTypeIn(eq(EVENT_ID), any())).thenReturn(List.of());
    }

    private static VirtualRoundsRequest request(LocalDateTime... times) {
        List<VirtualRoundsRequest.Round> rounds = new ArrayList<>();
        for (int i = 0; i < times.length; i += 2) {
            rounds.add(new VirtualRoundsRequest.Round(times[i], times[i + 1]));
        }
        return new VirtualRoundsRequest(rounds);
    }

    private void assertError(VirtualRoundsRequest request, ErrorCode code) {
        assertThatThrownBy(() -> service.replaceRounds(EVENT_ID, request))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
        verify(policyRepository, never()).deleteAll(any());
        verify(policyRepository, never()).saveAll(any());
    }

    private static List<RegistrationActionPolicyInput> inputs(LocalDateTime start, LocalDateTime deadline) {
        return List.of(Action.MODIFY, Action.PAYMENT, Action.REFUND).stream()
                .map(a -> new RegistrationActionPolicyInput("x", EVENT_ID, a, start, deadline, deadline, true)).toList();
    }

    private static RegistrationActionPolicy row(RegistrationActionType type, LocalDateTime start, LocalDateTime end,
            LocalDateTime effectiveFrom, boolean enabled) {
        return RegistrationActionPolicy.builder().eventId(EVENT_ID).actionType(type)
                .registrationStartAt(start).registrationEndAt(end).effectiveFrom(effectiveFrom).enabled(enabled).build();
    }

    /** 버추얼런이 아닌 대회는 거절한다. */
    @Test
    void rejectsNonVirtualEvent() {
        when(event.getEventFormat()).thenReturn(EventFormat.OFFLINE);
        assertError(request(T1, T2), ErrorCode.VIRTUAL_EVENT_REQUIRED);
    }

    /** 접수 마감이 없는 대회는 차수를 만들 수 없다. */
    @Test
    void rejectsMissingRegistDeadline() {
        when(event.getRegistDeadline()).thenReturn(null);
        assertError(request(T1, T2), ErrorCode.VIRTUAL_ROUND_INVALID);
    }

    /** 시작이 마감보다 늦거나 차수가 겹치거나 범위를 벗어나면 거절한다. */
    @Test
    void rejectsInvalidIntervals() {
        assertError(request(T2, T1), ErrorCode.VIRTUAL_ROUND_INVALID);
        assertError(request(T1, T3, T2, T4), ErrorCode.VIRTUAL_ROUND_INVALID);
        assertError(request(T1, END.plusSeconds(1)), ErrorCode.VIRTUAL_ROUND_INVALID);
        assertError(request(T1.plusDays(1), T2), ErrorCode.VIRTUAL_ROUND_INVALID);
    }

    /** 마감된 차수를 요청에서 빼거나 바꾸면 거절한다. */
    @Test
    void closedRoundMustBePreserved() {
        when(serverTimeProvider.currentDateTime()).thenReturn(T2.plusHours(1));
        when(policyReader.loadEnabledRegistrationActionPolicies(eq(EVENT_ID), any(java.util.Collection.class))).thenReturn(inputs(T1, T2));

        assertError(request(T3, T4), ErrorCode.VIRTUAL_ROUND_CLOSED_IMMUTABLE);
        assertError(request(T1, T2.plusDays(1), T3, T4), ErrorCode.VIRTUAL_ROUND_CLOSED_IMMUTABLE);
    }

    /** 이미 지난 마감 시각으로 새 차수를 만들 수 없다. */
    @Test
    void newRoundCannotEndInThePast() {
        when(serverTimeProvider.currentDateTime()).thenReturn(T3.plusHours(1));
        assertError(request(T1, T2), ErrorCode.VIRTUAL_ROUND_CLOSED_IMMUTABLE);
    }

    /** 새 차수는 세 작업 행을 만들고 차수 사이와 마지막 뒤에 REGIST 행을 만든다. */
    @Test
    void createsRoundRowsAndRegistBlocks() {
        service.replaceRounds(EVENT_ID, request(T3, T4, T1, T2));

        ArgumentCaptor<List<RegistrationActionPolicy>> captor = ArgumentCaptor.forClass(List.class);
        verify(policyRepository).saveAll(captor.capture());
        List<RegistrationActionPolicy> saved = captor.getValue();
        assertThat(saved).hasSize(8);
        assertThat(saved).filteredOn(r -> r.getActionType() != RegistrationActionType.REGIST).allSatisfy(r -> {
            assertThat(r.isEnabled()).isTrue();
            assertThat(r.getEffectiveFrom()).isEqualTo(r.getRegistrationEndAt());
        });
        assertThat(saved).filteredOn(r -> r.getActionType() == RegistrationActionType.MODIFY)
                .extracting(RegistrationActionPolicy::getRegistrationStartAt, RegistrationActionPolicy::getRegistrationEndAt)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(T1, T2), org.assertj.core.groups.Tuple.tuple(T3, T4));
        assertThat(saved).filteredOn(r -> r.getActionType() == RegistrationActionType.REGIST)
                .extracting(RegistrationActionPolicy::getRegistrationStartAt, RegistrationActionPolicy::getRegistrationEndAt,
                        RegistrationActionPolicy::getEffectiveFrom)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(T2, T3, T2), org.assertj.core.groups.Tuple.tuple(T4, END, T4));
    }

    /** 마감된 차수의 행은 지우지 않고 다시 넣지도 않으며, 그 외 행은 비활성·REGIST 포함 모두 지운다. */
    @Test
    void keepsClosedRoundRowsAndReplacesTheRest() {
        when(serverTimeProvider.currentDateTime()).thenReturn(T2.plusHours(1));
        when(policyReader.loadEnabledRegistrationActionPolicies(eq(EVENT_ID), any(java.util.Collection.class))).thenReturn(inputs(T1, T2));
        RegistrationActionPolicy keep1 = row(RegistrationActionType.MODIFY, T1, T2, T2, true);
        RegistrationActionPolicy keep2 = row(RegistrationActionType.PAYMENT, T1, T2, T2, true);
        RegistrationActionPolicy keep3 = row(RegistrationActionType.REFUND, T1, T2, T2, true);
        RegistrationActionPolicy oldRegist = row(RegistrationActionType.REGIST, T2, END, T2, true);
        RegistrationActionPolicy disabled = row(RegistrationActionType.MODIFY, T3, T4, T4, false);
        RegistrationActionPolicy openRound = row(RegistrationActionType.PAYMENT, T3, T4, T4, true);
        when(policyRepository.findAllByEventIdAndActionTypeIn(eq(EVENT_ID), any()))
                .thenReturn(List.of(keep1, keep2, keep3, oldRegist, disabled, openRound));

        service.replaceRounds(EVENT_ID, request(T1, T2, T3, T4));

        ArgumentCaptor<List<RegistrationActionPolicy>> deleted = ArgumentCaptor.forClass(List.class);
        verify(policyRepository).deleteAll(deleted.capture());
        assertThat(deleted.getValue()).containsExactlyInAnyOrder(oldRegist, disabled, openRound);
        ArgumentCaptor<List<RegistrationActionPolicy>> saved = ArgumentCaptor.forClass(List.class);
        verify(policyRepository).saveAll(saved.capture());
        // 새 2차수 세 행 + 차수 사이 REGIST + 마지막 뒤 REGIST
        assertThat(saved.getValue()).hasSize(5);
        assertThat(saved.getValue()).noneMatch(r -> r.getRegistrationStartAt().equals(T1) && r.getActionType() != RegistrationActionType.REGIST);
    }

    /** 진행 중인 차수를 빼거나 시작을 옮기면 거절하고 정책 행을 건드리지 않는다. */
    @Test
    void startedRoundMustKeepItsStart() {
        when(serverTimeProvider.currentDateTime()).thenReturn(T3.plusHours(1));
        List<RegistrationActionPolicyInput> rows = new ArrayList<>(inputs(T1, T2));
        rows.addAll(inputs(T3, T4));
        when(policyReader.loadEnabledRegistrationActionPolicies(eq(EVENT_ID), any(java.util.Collection.class))).thenReturn(rows);

        assertError(request(T1, T2), ErrorCode.VIRTUAL_ROUND_STARTED_IMMUTABLE);
        assertError(request(T1, T2, T3.plusDays(1), T4), ErrorCode.VIRTUAL_ROUND_STARTED_IMMUTABLE);
    }

    /** 진행 중인 차수의 마감은 지금보다 뒤라면 옮길 수 있다. */
    @Test
    void startedRoundCanMoveItsDeadline() {
        when(serverTimeProvider.currentDateTime()).thenReturn(T3.plusHours(1));
        List<RegistrationActionPolicyInput> rows = new ArrayList<>(inputs(T1, T2));
        rows.addAll(inputs(T3, T4));
        when(policyReader.loadEnabledRegistrationActionPolicies(eq(EVENT_ID), any(java.util.Collection.class))).thenReturn(rows);

        service.replaceRounds(EVENT_ID, request(T1, T2, T3, T4.plusDays(1)));

        verify(policyRepository).saveAll(any());
    }

    /** 세 작업이 맞지 않는 차수 행이 있으면 어느 행도 지우지 않고 거절한다. */
    @Test
    void rejectsRowsOutsideRounds() {
        List<RegistrationActionPolicyInput> rows = new ArrayList<>(inputs(T1, T2));
        rows.add(new RegistrationActionPolicyInput("x", EVENT_ID, Action.PAYMENT, T3, T4, T4, true));
        when(policyReader.loadEnabledRegistrationActionPolicies(eq(EVENT_ID), any(java.util.Collection.class))).thenReturn(rows);

        assertError(request(T1, T2, T3, T4), ErrorCode.VIRTUAL_ROUND_POLICY_INCONSISTENT);
    }
}
