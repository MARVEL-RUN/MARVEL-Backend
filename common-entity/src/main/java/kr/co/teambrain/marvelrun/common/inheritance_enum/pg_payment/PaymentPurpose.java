package kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment;

/** 돈을 받는 주문의 목적을 구분한다. 환불은 PaymentCancel에 기록한다. */
public enum PaymentPurpose {
    /** 아직 확정하지 않은 참가자의 최초 참가비. */
    REGISTRATION_TRY,
    /** 이미 참가가 확정된 참가자의 추가 납부액. */
    ADDITIONAL_PAYMENT,
    /** 최초 참가비와 기존 참가자의 추가 납부액을 함께 받는 단체 주문. */
    MIXED_PAYMENT
}
