package kr.co.teambrain.marvelrun.user.event.policy;

import kr.co.teambrain.marvelrun.common.inheritance_enum.EventStatus;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyModels.Action;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyModels.EventInput;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyModels.RegistrationActionPolicyInput;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 마이페이지 수정·환불 마감 시각이 평가기의 차단 기준(신청일이 든 차수의 effectiveFrom, 접수 마감)과 맞는지 본다. */
class RegistrationActionPolicyDeadlineTest {

    private final RegistrationActionPolicyEvaluator evaluator = new RegistrationActionPolicyEvaluator();
    private final LocalDateTime round1Start = LocalDateTime.of(2026, 11, 1, 0, 0);
    private final LocalDateTime round1End = LocalDateTime.of(2026, 11, 20, 0, 0);
    private final LocalDateTime round2End = LocalDateTime.of(2026, 12, 10, 0, 0);
    private final LocalDateTime registDeadline = LocalDateTime.of(2026, 12, 31, 0, 0);
    private final EventInput event = new EventInput("e", EventStatus.OPEN, round1Start, registDeadline, null);

    private final List<RegistrationActionPolicyInput> rounds = List.of(
            new RegistrationActionPolicyInput("m1", "e", Action.MODIFY, round1Start, round1End, round1End, true),
            new RegistrationActionPolicyInput("r1", "e", Action.REFUND, round1Start, round1End, round1End.minusDays(3), true),
            new RegistrationActionPolicyInput("m2", "e", Action.MODIFY, round1End, round2End, round2End, true),
            new RegistrationActionPolicyInput("off", "e", Action.MODIFY, round1Start, round1End, round1Start, false),
            new RegistrationActionPolicyInput("other", "x", Action.MODIFY, round1Start, round1End, round1Start, true));

    @Test
    void 신청일이_든_차수의_마감을_돌려준다() {
        LocalDateTime inRound1 = LocalDateTime.of(2026, 11, 5, 10, 0);
        LocalDateTime inRound2 = round1End; // 끝 시각은 다음 차수에 든다

        assertThat(evaluator.deadlineOf(event, inRound1, Action.MODIFY, rounds)).isEqualTo(round1End);
        assertThat(evaluator.deadlineOf(event, inRound1, Action.REFUND, rounds)).isEqualTo(round1End.minusDays(3));
        assertThat(evaluator.deadlineOf(event, inRound2, Action.MODIFY, rounds)).isEqualTo(round2End);
    }

    @Test
    void 맞는_차수가_없으면_접수_마감을_돌려준다() {
        LocalDateTime inRound2 = LocalDateTime.of(2026, 11, 25, 0, 0);

        assertThat(evaluator.deadlineOf(event, inRound2, Action.REFUND, rounds)).isEqualTo(registDeadline);
        assertThat(evaluator.deadlineOf(new EventInput("e", EventStatus.OPEN, round1Start, null, null),
                inRound2, Action.REFUND, rounds)).isNull();
    }
}
