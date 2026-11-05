package kr.co.teambrain.marvelrun.admin.event.policy;

import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.event.policy.RegistrationActionPolicyModels.Action;
import kr.co.teambrain.marvelrun.admin.event.policy.RegistrationActionPolicyModels.RegistrationActionPolicyInput;
import kr.co.teambrain.marvelrun.admin.event.policy.VirtualRounds.Range;
import kr.co.teambrain.marvelrun.admin.event.policy.VirtualRounds.Round;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 정책 행에서 차수를 읽는 규칙과 차수 변경 요청 검증을 확인한다. */
class VirtualRoundsTest {
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 11, 1, 0, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 11, 20, 17, 0);
    private static final LocalDateTime T3 = LocalDateTime.of(2026, 11, 23, 0, 0);
    private static final LocalDateTime T4 = LocalDateTime.of(2026, 11, 27, 17, 0);
    private static final LocalDateTime END = LocalDateTime.of(2027, 1, 19, 23, 59, 59);

    private static List<RegistrationActionPolicyInput> round(LocalDateTime start, LocalDateTime deadline) {
        List<RegistrationActionPolicyInput> rows = new ArrayList<>();
        for (Action action : List.of(Action.MODIFY, Action.PAYMENT, Action.REFUND)) {
            rows.add(row(action, start, deadline, deadline, true));
        }
        return rows;
    }

    private static RegistrationActionPolicyInput row(Action action, LocalDateTime start, LocalDateTime end,
            LocalDateTime effectiveFrom, boolean enabled) {
        return new RegistrationActionPolicyInput("id", "e", action, start, end, effectiveFrom, enabled);
    }

    /** 시작이 늦은 차수를 먼저 넣어도 시작 순으로 1부터 번호를 매긴다. */
    @Test
    void groupsRowsAndNumbersByStart() {
        List<RegistrationActionPolicyInput> rows = new ArrayList<>(round(T3, T4));
        rows.addAll(round(T1, T2));

        List<Round> rounds = VirtualRounds.fromPolicies(rows);

        assertThat(rounds).extracting(Round::number, Round::startAt, Round::deadline)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, T1, T2), org.assertj.core.groups.Tuple.tuple(2, T3, T4));
    }

    /** 세 작업이 모두 없는 묶음, 비활성 행, REGIST 행은 차수가 아니다. */
    @Test
    void ignoresIncompleteDisabledAndRegistRows() {
        List<RegistrationActionPolicyInput> rows = new ArrayList<>(round(T1, T2));
        rows.add(row(Action.MODIFY, T3, T4, T4, true));
        rows.add(row(Action.PAYMENT, T3, T4, T4, true));
        rows.add(row(Action.MODIFY, T2, T3, T3, false));
        rows.add(row(Action.PAYMENT, T2, T3, T3, true));
        rows.add(row(Action.REFUND, T2, T3, T3, true));
        rows.add(row(Action.REGIST, T2, T3, T2, true));

        assertThat(VirtualRounds.fromPolicies(rows)).hasSize(1);
    }

    /** 마감 시각이 같은 차수가 하나일 때만 찾고 없으면 거절한다. */
    @Test
    void requireByDeadline() {
        List<Round> rounds = VirtualRounds.fromPolicies(round(T1, T2));

        assertThat(VirtualRounds.requireByDeadline(rounds, T2).number()).isEqualTo(1);
        assertThatThrownBy(() -> VirtualRounds.requireByDeadline(rounds, T3))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VIRTUAL_ROUND_NOT_FOUND));
    }

    /** 차수 사이와 마지막 차수 뒤가 신규 신청 차단 구간이다. */
    @Test
    void registBlocksCoverGapsAndTail() {
        List<Range> blocks = VirtualRounds.registBlocks(List.of(new Range(T1, T2), new Range(T3, T4)), END);

        assertThat(blocks).containsExactly(new Range(T2, T3), new Range(T4, END));
    }

    /** 차수가 맞닿으면 사이 구간이 없고 마지막 마감이 접수 마감이면 뒤 구간도 없다. */
    @Test
    void registBlocksSkipEmptyIntervals() {
        assertThat(VirtualRounds.registBlocks(List.of(new Range(T1, T2), new Range(T2, END)), END)).isEmpty();
    }

    /** 요청을 시작 순으로 정렬해 돌려준다. */
    @Test
    void validateSortsRequest() {
        List<Range> sorted = VirtualRounds.validateRequest(List.of(new Range(T3, T4), new Range(T1, T2)), List.of(), T1, END, T1);

        assertThat(sorted).containsExactly(new Range(T1, T2), new Range(T3, T4));
    }
}
