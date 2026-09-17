package kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment;

/**
 * Payment가 생성된 업무 목적.
 *
 * "어떤 방식으로 결제했는가"가 아니라
 * "왜 새로운 Payment를 생성하여 돈을 받는가"를 구분한다.
 *
 * 환불 및 부분환불은 PaymentCancel의 책임이므로 포함하지 않는다.
 */
public enum PaymentPurpose {

    // 최초 참가 신청에 대한 참가비 결제
    REGISTRATION_TRY,

    // 기존 결제 이후 계약금액 증가 등에 따른 추가 결제
    // ex. 단체신청 인원 추가, 신청내역 수정 추가금 등
    ADDITIONAL_PAYMENT
}