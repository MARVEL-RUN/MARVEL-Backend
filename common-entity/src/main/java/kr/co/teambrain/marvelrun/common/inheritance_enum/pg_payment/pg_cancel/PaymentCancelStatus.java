package kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel;

/**
 * MarvelRun 서버 관점에서 하나의 Payment 취소 작업이
 * 현재 어느 처리 단계에 있는지를 나타낸다.
 *
 * Toss Cancel 객체의 cancelStatus와 동일한 개념이 아니다.
 * 네트워크 timeout, DB 반영 실패 등의 상황까지 표현하기 위한
 * 애플리케이션 내부 처리 상태이다.
 */
public enum PaymentCancelStatus {

    // 취소 row가 생성되고 Toss 취소 API 처리를 진행 중인 상태
    PROCESSING,

    // Toss 취소 성공을 확인하고 MarvelRun DB 반영까지 완료한 상태
    DONE,

    // Toss에서 취소되지 않았음이 명확하게 확인된 상태
    FAILED,

    // 실제 취소 성공 여부를 현재 MarvelRun 서버가 확정하지 못한 상태
    UNKNOWN
}