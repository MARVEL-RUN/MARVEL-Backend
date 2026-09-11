package kr.co.teambrain.marvelrun.common.inheritance_enum;

public enum PaymentStatus {
    UNPAID, // 미결제
    COMPLETED, // 결제완료
    MUST_CHECK, // 확인 필요
    NEED_PARTITIAL_REFUND,// 차액 환불 요청
    NEED_REFUND,// 전액 환불 요청
    REFUNDED // 전액 환불 완료
}
