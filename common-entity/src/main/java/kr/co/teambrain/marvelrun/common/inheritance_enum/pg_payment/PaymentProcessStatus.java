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
    UNKNOWN,

    /**
     * 승인 시작 전에 주문이 무효화되어 더 이상 결제할 수 없는 상태.(관리자 홀딩해제, 사용자의 신청내역 수정 등)
     *
     * 미결제 확보 반환 등에 의해 사용되며,
     * 실제 승인 실패나 승인된 결제의 취소를 의미하지 않는다.
     * 다시 결제하려면 새로운 Payment를 생성한다.
     */
    INVALIDATED
}