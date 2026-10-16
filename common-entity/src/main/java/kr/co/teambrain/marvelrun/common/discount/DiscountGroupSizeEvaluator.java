package kr.co.teambrain.marvelrun.common.discount;

/** 인원 제거 결과를 검증하되 결제 전 후보와 확정 참가자를 구분한다. */
public final class DiscountGroupSizeEvaluator {
    /** 일반 인원도 포함한 호출자 스냅샷으로 최소 인원 유지 여부를 판단한다. */
    public boolean allowsGroupChange(Integer minimum, boolean initialOrEntirelyUnpaid,
                                     int finalCandidateCount, int confirmedBefore, int confirmedAfter,
                                     boolean removesParticipant, boolean cancelsEntireGroup) {
        if (minimum == null || cancelsEntireGroup) {
            return true;
        }
        if (initialOrEntirelyUnpaid) {
            return finalCandidateCount >= minimum;
        }
        if (!removesParticipant) {
            return true;
        }
        return confirmedAfter >= minimum || confirmedAfter == confirmedBefore;
    }
}
