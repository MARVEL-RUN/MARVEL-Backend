package kr.co.teambrain.marvelrun.common.discount;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 단체 최소 인원과 전체 취소 예외를 검증한다. */
class DiscountGroupSizeEvaluatorTest {
    private final DiscountGroupSizeEvaluator evaluator = new DiscountGroupSizeEvaluator();

    /** 신규 신청은 일반 참가자를 포함한 후보 인원으로 판정한다. */
    @Test
    void initialCandidatesMustReachMinimum() {
        assertFalse(evaluator.allowsGroupChange(5, true, 4, 0, 0, false, false));
        assertTrue(evaluator.allowsGroupChange(5, true, 5, 0, 0, false, false));
    }

    /** 미결제 신규 인원은 확정 참가자 제거를 대체하지 못한다. */
    @Test
    void unpaidReplacementCannotPreserveFiveConfirmedMembers() {
        assertFalse(evaluator.allowsGroupChange(5, false, 5, 5, 4, true, false));
        assertTrue(evaluator.allowsGroupChange(5, false, 5, 6, 5, true, false));
    }

    /** 추가결제·부분환불 중 기존 확정 참가자는 계속 포함되며 정보 수정은 차단하지 않는다. */
    @Test
    void retainedConfirmedMembersAndWholeCancellationAreAllowed() {
        assertTrue(evaluator.allowsGroupChange(5, false, 5, 5, 5, false, false));
        assertTrue(evaluator.allowsGroupChange(5, false, 0, 5, 0, true, true));
        assertTrue(evaluator.allowsGroupChange(null, false, 1, 2, 1, true, false));
    }
}
