package kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel;

/**
 * 해당 Payment 취소가 발생한 MarvelRun의 업무상 목적을 나타낸다.
 *
 * 동일한 환불이라도 참가 자체를 취소하기 위한 것인지,
 * 참가비 변경에 따른 차액 환불인지에 따라
 * Registration의 후속 상태가 달라질 수 있다.
 */
public enum PaymentCancelPurpose {

    // 참가 신청 자체를 취소하기 위한 환불
    REGISTRATION_CANCELLATION,

    // 종목 수정 등 최종 계약금액 변경으로 발생한 차액 환불
    PRICE_ADJUSTMENT,

    // 관리자가 기타 운영상 사유로 개별적으로 수행한 환불
    ADMIN_ADJUSTMENT,

    // 대회 정책에 의거해 발생한 자동 환불(종목 비용 축소에 따른 기존 결제자들에 대한 자동 일괄 환불처리 등)
    EVENT_POLICY
}