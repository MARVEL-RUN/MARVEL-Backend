package kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel;

/**
 * 해당 취소 요청이 Payment의 환불 가능한 잔액 전체를
 * 취소하는 요청인지, 일부 금액만 취소하는 요청인지 나타낸다.
 */
public enum PaymentCancelType {

    // 해당 시점의 환불 가능한 잔액 전체를 취소하는 요청
    FULL,

    // 환불 가능한 잔액 중 일부 금액만 취소하는 요청
    PARTIAL
}