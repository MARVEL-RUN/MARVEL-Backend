package kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_log;

/**
 * Payment 또는 PaymentCancel과 관련하여
 * MarvelRun에서 수행된 하나의 처리 종류를 나타낸다.
 *
 * PaymentProcessLog는 이 값을 기준으로
 * 결제 승인, 취소, Webhook, 정합성 복구 등의
 * 처리 이력을 구분한다.
 */
public enum PaymentProcessType {

    // 새로운 Payment가 생성되고 결제 준비가 완료됨
    PAYMENT_PREPARED,

    // Toss 결제 승인(confirm) 요청을 시작함
    CONFIRM_REQUESTED,

    // Toss 결제 승인 성공을 확인함
    CONFIRM_SUCCEEDED,

    // Toss 결제 승인이 실패했음이 명확하게 확인됨
    CONFIRM_FAILED,

    // Toss 결제 승인 성공 여부를 현재 확정할 수 없음
    CONFIRM_UNKNOWN,

    // Toss 결제 취소 요청을 시작함
    CANCEL_REQUESTED,

    // Toss 결제 취소 성공을 확인함
    CANCEL_SUCCEEDED,

    // Toss 결제 취소가 실패했음이 명확하게 확인됨
    CANCEL_FAILED,

    // Toss 결제 취소 성공 여부를 현재 확정할 수 없음
    CANCEL_UNKNOWN,

    // Toss Webhook 요청을 수신함
    WEBHOOK_RECEIVED,

    // Toss와 MarvelRun 상태의 정합성 확인을 시작함
    RECONCILIATION_STARTED,

    // 정합성 확인 결과 두 시스템의 상태가 일치함
    RECONCILIATION_MATCHED,

    // 불일치를 발견하고 MarvelRun 데이터를 보정함
    RECONCILIATION_CORRECTED
}
