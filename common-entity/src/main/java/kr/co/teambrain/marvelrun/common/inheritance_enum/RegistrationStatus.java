package kr.co.teambrain.marvelrun.common.inheritance_enum;

public enum RegistrationStatus {

    /**
     * 관리자에 의해 편입 등의 사유로 추가 생성되어 결제를 대기중인 상태. PAYMENT_PENDING과 달리 만료 제약기간이 없음
     */
    PENDING,


    /**
     * !!기본값!!
     * 신청은 만들어졌지만 최초 결제가 완료되지 않은 상태.
     * 결제 만료 제약 기간 후 자동 삭제 처리됨.(영화관 좌석 방식)
     */
    PAYMENT_PENDING,

    /**
     * 현재 계약금액에 필요한 결제가 충족되어
     * 정상 참가가 확정된 상태.
     */
    CONFIRMED,

    /**
     * 최초/기존 결제 이후 계약금액이 증가했거나
     * 기타 사유로 추가 결제가 필요한 상태.
     */
    ADDITIONAL_PAYMENT_REQUIRED,

    /**
     * 참가 자체의 취소가 요청됐으며
     * 필요한 환불 등 후속 처리가 아직 완료되지 않은 상태.
     */
    CANCELLATION_PENDING,

    /**
     * 참가 취소 및 필요한 후속 처리가 완료된 상태.
     */
    CANCELED,

    /**
     * 최초 결제가 완료되지 않은 상태로 유효기간이 종료된 신청.
     */
    EXPIRED
}