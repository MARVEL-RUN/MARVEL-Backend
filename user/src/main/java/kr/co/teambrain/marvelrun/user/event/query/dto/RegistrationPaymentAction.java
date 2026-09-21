package kr.co.teambrain.marvelrun.user.event.query.dto;

/** 결제 상태와 구분하여 접수 확인 화면의 후속 동작을 안내한다. */
public enum RegistrationPaymentAction {
    PREPARE_PAYMENT, WAIT, NONE, PAYMENT_CLOSED, CONTACT_SUPPORT
}
