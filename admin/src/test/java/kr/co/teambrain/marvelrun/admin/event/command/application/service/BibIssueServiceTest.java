package kr.co.teambrain.marvelrun.admin.event.command.application.service;

import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.BibIssueRequest;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.BibIssueResponse;
import kr.co.teambrain.marvelrun.admin.event.command.repository.BibIssueRepository;
import kr.co.teambrain.marvelrun.admin.event.command.repository.BibIssueRepository.Assignment;
import kr.co.teambrain.marvelrun.admin.event.command.repository.BibIssueRepository.Sequence;
import kr.co.teambrain.marvelrun.admin.event.command.repository.BibIssueRepository.Target;
import kr.co.teambrain.marvelrun.admin.event.command.repository.EventCommandRepository;
import kr.co.teambrain.marvelrun.admin.event.policy.RegistrationActionPolicyModels.Action;
import kr.co.teambrain.marvelrun.admin.event.policy.RegistrationActionPolicyModels.RegistrationActionPolicyInput;
import kr.co.teambrain.marvelrun.admin.event.query.support.RegistrationActionPolicyReader;
import kr.co.teambrain.marvelrun.common.inheritance_enum.EventFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 배번 발급의 검사 순서, 거절 조건, 종목별 번호 계산을 확인한다. */
@ExtendWith(MockitoExtension.class)
class BibIssueServiceTest {
    private static final String EVENT_ID = "e1";
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 1, 0, 0);
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 11, 20, 17, 0);
    private static final LocalDateTime AFTER = DEADLINE.plusHours(1);

    @Mock ServerTimeProvider serverTimeProvider;
    @Mock EventCommandRepository eventRepository;
    @Mock RegistrationActionPolicyReader policyReader;
    @Mock BibIssueRepository repository;

    private BibIssueService service() {
        return new BibIssueService(serverTimeProvider, eventRepository, policyReader, repository);
    }

    private Event event(EventFormat format) {
        Event event = mock(Event.class);
        when(event.getEventFormat()).thenReturn(format);
        return event;
    }

    private static List<RegistrationActionPolicyInput> round(LocalDateTime start, LocalDateTime deadline) {
        return List.of(Action.MODIFY, Action.PAYMENT, Action.REFUND).stream()
                .map(action -> new RegistrationActionPolicyInput("p", EVENT_ID, action, start, deadline, deadline, true))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private void givenVirtualRound(LocalDateTime now) {
        when(serverTimeProvider.currentDateTime()).thenReturn(now);
        Event event = event(EventFormat.VIRTUAL);
        when(eventRepository.findByIdForUpdate(EVENT_ID)).thenReturn(Optional.of(event));
        when(policyReader.loadEnabledRegistrationActionPolicies(eq(EVENT_ID), any(Collection.class)))
                .thenReturn(round(START, DEADLINE));
    }

    private void assertError(ErrorCode code) {
        assertThatThrownBy(() -> service().issue(EVENT_ID, new BibIssueRequest(DEADLINE)))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    /** 마감 전이면 상향 대기·순번·배번을 건드리지 않고 거절한다. */
    @Test
    void futureDeadlineIsRejectedBeforeIssuing() {
        givenVirtualRound(DEADLINE.minusMinutes(1));

        assertError(ErrorCode.BIB_ISSUE_ROUND_NOT_CLOSED);
        verify(repository, never()).countPendingUpgrades(any(), any());
        verify(repository, never()).lockHomepageSequences(any());
        verify(repository, never()).updateBibs(any(), any());
    }

    /** 없는 대회는 마감 검사보다 먼저 EVENT_NOT_FOUND다. */
    @Test
    void missingEventComesBeforeDeadlineCheck() {
        when(serverTimeProvider.currentDateTime()).thenReturn(DEADLINE.minusDays(1));
        when(eventRepository.findByIdForUpdate(EVENT_ID)).thenReturn(Optional.empty());

        assertError(ErrorCode.EVENT_NOT_FOUND);
    }

    /** 오프라인 대회는 마감 검사보다 먼저 VIRTUAL_EVENT_REQUIRED다. */
    @Test
    void offlineEventComesBeforeDeadlineCheck() {
        when(serverTimeProvider.currentDateTime()).thenReturn(DEADLINE.minusDays(1));
        Event event = event(EventFormat.OFFLINE);
        when(eventRepository.findByIdForUpdate(EVENT_ID)).thenReturn(Optional.of(event));

        assertError(ErrorCode.VIRTUAL_EVENT_REQUIRED);
    }

    /** 어느 차수 마감과도 맞지 않으면 마감 검사보다 먼저 VIRTUAL_ROUND_NOT_FOUND다. */
    @Test
    @SuppressWarnings("unchecked")
    void unknownRoundComesBeforeDeadlineCheck() {
        when(serverTimeProvider.currentDateTime()).thenReturn(DEADLINE.minusDays(1));
        Event event = event(EventFormat.VIRTUAL);
        when(eventRepository.findByIdForUpdate(EVENT_ID)).thenReturn(Optional.of(event));
        when(policyReader.loadEnabledRegistrationActionPolicies(eq(EVENT_ID), any(Collection.class)))
                .thenReturn(round(START, DEADLINE.plusDays(1)));

        assertError(ErrorCode.VIRTUAL_ROUND_NOT_FOUND);
    }

    /** 상향 대기가 남아 있으면 순번을 잠그거나 배번을 쓰지 않고 거절한다. */
    @Test
    void pendingUpgradeRejectsWithoutUpdate() {
        givenVirtualRound(AFTER);
        when(repository.countPendingUpgrades(EVENT_ID, DEADLINE)).thenReturn(1);

        assertError(ErrorCode.BIB_ISSUE_UPGRADE_PENDING);
        verify(repository, never()).lockHomepageSequences(any());
        verify(repository, never()).updateBibs(any(), any());
    }

    /** 대상 종목 하나라도 순번 행이 없으면 아무 배번도 쓰지 않는다. */
    @Test
    void missingSequenceRejectsAll() {
        givenVirtualRound(AFTER);
        when(repository.lockHomepageSequences(EVENT_ID)).thenReturn(Map.of("a", new Sequence(10001, null)));
        when(repository.findTargets(EVENT_ID, DEADLINE)).thenReturn(List.of(
                new Target("e1", "a", "어벤저스", 1), new Target("e2", "x", "엑스맨", 2)));

        assertError(ErrorCode.BIB_SEQUENCE_NOT_CONFIGURED);
        verify(repository, never()).updateBibs(any(), any());
    }

    /** 처음 발급은 시작 번호부터, 이어 발급은 마지막 번호 다음부터 종목별로 1씩 늘린다. */
    @Test
    @SuppressWarnings("unchecked")
    void assignsNumbersPerCategory() {
        givenVirtualRound(AFTER);
        when(repository.lockHomepageSequences(EVENT_ID)).thenReturn(Map.of(
                "a", new Sequence(10001, null), "x", new Sequence(20001, 20005)));
        when(repository.findTargets(EVENT_ID, DEADLINE)).thenReturn(List.of(
                new Target("r1-a", "a", "어벤저스", 1), new Target("r1-x", "x", "엑스맨", 2),
                new Target("r2-a", "a", "어벤저스", 1), new Target("r3-x", "x", "엑스맨", 2)));
        when(repository.countAlreadyIssued(EVENT_ID, DEADLINE)).thenReturn(10);

        BibIssueResponse response = service().issue(EVENT_ID, new BibIssueRequest(DEADLINE));

        ArgumentCaptor<List<Assignment>> assignments = ArgumentCaptor.forClass(List.class);
        verify(repository).updateBibs(assignments.capture(), eq(AFTER));
        assertThat(assignments.getValue()).containsExactly(
                new Assignment("r1-a", "10001"), new Assignment("r1-x", "20006"),
                new Assignment("r2-a", "10002"), new Assignment("r3-x", "20007"));
        verify(repository).updateLastNumbers(EVENT_ID, Map.of("a", 10002, "x", 20007));
        assertThat(response.issuedCount()).isEqualTo(4);
        assertThat(response.alreadyIssuedCount()).isEqualTo(6);
        assertThat(response.perCategory()).extracting(BibIssueResponse.PerCategory::eventCategoryId,
                        BibIssueResponse.PerCategory::issuedCount, BibIssueResponse.PerCategory::firstNumber,
                        BibIssueResponse.PerCategory::lastNumber)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("a", 2, 10001, 10002),
                        org.assertj.core.groups.Tuple.tuple("x", 2, 20006, 20007));
    }

    /** 발급할 항목이 없으면 순번을 갱신하지 않는다. */
    @Test
    void noTargetsSkipsSequenceUpdate() {
        givenVirtualRound(AFTER);
        when(repository.lockHomepageSequences(EVENT_ID)).thenReturn(Map.of());
        when(repository.findTargets(EVENT_ID, DEADLINE)).thenReturn(List.of());

        BibIssueResponse response = service().issue(EVENT_ID, new BibIssueRequest(DEADLINE));

        assertThat(response.issuedCount()).isZero();
        verify(repository, never()).updateLastNumbers(any(), anyMap());
    }

    /** 배번 갱신 건수가 어긋나 난 동시 수정 오류는 그대로 올라가 전체를 되돌린다. */
    @Test
    void concurrentModificationPropagates() {
        givenVirtualRound(AFTER);
        when(repository.lockHomepageSequences(EVENT_ID)).thenReturn(Map.of("a", new Sequence(10001, null)));
        when(repository.findTargets(EVENT_ID, DEADLINE)).thenReturn(List.of(new Target("r1-a", "a", "어벤저스", 1)));
        doThrow(new CustomException(ErrorCode.CONCURRENT_MODIFICATION)).when(repository).updateBibs(any(), any());

        assertError(ErrorCode.CONCURRENT_MODIFICATION);
        verify(repository, never()).updateLastNumbers(any(), anyMap());
    }
}
