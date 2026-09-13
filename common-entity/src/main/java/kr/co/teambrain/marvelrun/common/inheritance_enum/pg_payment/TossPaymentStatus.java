package kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment;

/**
 * Toss Payments가 정의하는 실제 Payment의 금융 상태.
 *
 * MarvelRun 내부 처리 상태인 PaymentProcessStatus와 구분하며,
 * Toss 조회/confirm/webhook 결과에서 확인한 값을 저장한다.
 */
public enum TossPaymentStatus {

    // 결제를 생성했지만 아직 사용자 인증을 시작하지 않은 상태
    READY,

    // 결제수단 인증이 진행 중인 상태
    IN_PROGRESS,

    // 가상계좌 등 입금을 기다리고 있는 상태
    WAITING_FOR_DEPOSIT,

    // 결제 승인이 정상적으로 완료된 상태
    DONE,

    // 결제금액 전체가 취소된 상태
    CANCELED,

    // 결제금액 중 일부만 취소된 상태
    PARTIAL_CANCELED,

    // 결제 승인 과정에서 실패하여 결제가 중단된 상태
    ABORTED,

    // 정해진 시간 안에 결제가 완료되지 않아 만료된 상태
    EXPIRED
}