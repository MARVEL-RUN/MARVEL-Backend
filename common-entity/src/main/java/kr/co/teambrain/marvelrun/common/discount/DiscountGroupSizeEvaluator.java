package kr.co.teambrain.marvelrun.common.discount;

/** 인원 제거 결과를 검증하되 결제 전 후보와 확정 참가자를 구분한다. */
public final class DiscountGroupSizeEvaluator {
    /** 일반 인원도 포함한 호출자 스냅샷으로 최소 인원 유지 여부를 판단한다. */
    public boolean allowsGroupChange(Integer minimum, boolean initialOrEntirelyUnpaid,
        int finalCandidateCount, int confirmedBefore, int confirmedAfter,
        boolean removesParticipant, boolean cancelsEntireGroup) {

        // 인원 제한이 없거나 단체 전체를 취소하는 경우 이 인원 검증은 통과한다.
        if (minimum == null || cancelsEntireGroup) {
            return true;
        }

        // 최초 또는 전원 미결제 상태에서는 최종 신청 인원으로 판정한다.
        if (initialOrEntirelyUnpaid) {
            return finalCandidateCount >= minimum;
        }

        // 확정 인원이 있는 단체는 제거 시 확정 인원 유지 여부를 추가로 검증한다.
        if (!removesParticipant) {
            return true;
        }
        return confirmedAfter >= minimum || confirmedAfter == confirmedBefore;
    }
}
