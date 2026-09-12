package kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment;

/**
 * Toss를 통해 실제 사용된 결제수단의 대분류.
 *
 * 결제 목적(PaymentPurpose)과는 별개의 개념이다.
 * 실제 승인 결과를 기준으로 Payment에 저장한다.
 */
public enum PaymentMethod {

    // 일반 신용카드 또는 체크카드 결제
    CARD,

    // 토스페이, 카카오페이, 네이버페이 등의 간편결제
    EASY_PAY
}