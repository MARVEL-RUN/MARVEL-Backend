package kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_log;

/**
 * PaymentProcessLog가 어떤 실행 경로에서 발생했는지를 나타낸다.
 *
 * 동일한 금융 상태 변경이라도 사용자 API 요청,
 * Webhook, 관리자 작업, 정합성 복구 작업 등을 구분하기 위해 사용한다.
 */
public enum PaymentProcessSource {

    // 사용자 또는 Frontend API 요청에서 발생한 처리
    API,

    // Toss Webhook 수신으로 발생한 처리
    WEBHOOK,

    // 정합성 검사 및 복구 작업에서 발생한 처리
    RECONCILIATION,

    // 관리자 수동 작업에서 발생한 처리
    ADMIN,

    // 일괄환불 등 Batch 작업에서 발생한 처리
    BATCH
}