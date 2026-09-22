package kr.co.teambrain.marvelrun.admin.payment.command.infrastructure.toss.refund;

/** 외부 통신 결과를 반환한다. VERIFIED도 DB 반영 전이므로 PaymentCancel.DONE과 구분한다. */
public record TossCancelOutcome(
        Kind kind, VerifiedTossCancellation cancellation,
        Integer httpStatus, String errorCode, String message) {

    /** 외부 성공 확인, 명확한 거절, 결과불명을 구분한다. */
    public enum Kind { VERIFIED, REJECTED, UNKNOWN }

    /** 검증된 외부 취소 결과를 구성한다. */
    public static TossCancelOutcome verified(VerifiedTossCancellation cancellation) {
        return new TossCancelOutcome(Kind.VERIFIED, cancellation, 200, null, null);
    }

    /** 문서에서 확인한 명확한 거절만 반환한다. */
    public static TossCancelOutcome rejected(int status, String code) {
        return new TossCancelOutcome(Kind.REJECTED, null, status, code,
                "Toss에서 취소 요청을 거절했습니다.");
    }

    /** 처리 여부를 단정할 수 없으면 순결제금액을 변경하지 않도록 결과불명을 반환한다. */
    public static TossCancelOutcome unknown(Integer status, String code) {
        return new TossCancelOutcome(Kind.UNKNOWN, null, status, code,
                "취소 결과를 확정하지 못했습니다. 추가 요청 전에 확인이 필요합니다.");
    }
}