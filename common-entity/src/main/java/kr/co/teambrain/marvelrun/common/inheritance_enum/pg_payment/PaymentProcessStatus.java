package kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment;

/**
 * MarvelRun 서버 관점에서 Payment 처리 작업이
 * 현재 어느 단계까지 완료되었는지 나타냄.
 *
 * Toss가 정의하는 실제 금융 상태와는 별개의 상태.
 * 네트워크 장애나 DB 반영 실패처럼
 * Toss 상태만으로 표현할 수 없는 본 서비스 내 시스템 상태를 관리.
 */
public enum PaymentProcessStatus {

    // Payment가 생성되었으며 아직 Toss confirm을 시작하지 않은 상태
    READY,

    // Toss confirm 요청을 시작했으며 결과를 처리 중인 상태
    CONFIRMING,

    // Toss 결과 확인과 MarvelRun DB 반영까지 정상적으로 완료된 상태
    COMPLETED,

    // 결제가 실제로 성공하지 않았음이 명확하게 확인된 상태
    FAILED,

    // Toss에서 결제가 처리되었는지 현재 MarvelRun이 확정하지 못한 상태
    UNKNOWN
}