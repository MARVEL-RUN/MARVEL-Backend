package kr.co.teambrain.marvelrun.common.inheritance_enum;

/** 신청 상태의 저장 식별자와 운영 화면·엑셀용 한글 표시명을 정의한다. */
public enum RegistrationStatus {

    /**
     * 관리자에 의해 편입 등의 사유로 추가 생성되어 결제를 대기중인 상태. PAYMENT_PENDING과 달리 만료 제약기간이 없음
     */
    PENDING("결제 대기(만료 제한 없음)"),


    /**
     * !!기본값!!
     * 신청은 만들어졌지만 최초 결제가 완료되지 않은 상태.
     * 결제 만료 제약 기간 후 자동 삭제 처리됨.(영화관 좌석 방식)
     */
    PAYMENT_PENDING("최초 결제 대기"),

    /**
     * 현재 계약금액에 필요한 결제가 충족되어
     * 정상 참가가 확정된 상태.
     */
    CONFIRMED("참가 확정"),

    /**
     * 최초/기존 결제 이후 계약금액이 증가했거나
     * 기타 사유로 추가 결제가 필요한 상태.
     */
    ADDITIONAL_PAYMENT_REQUIRED("추가 결제 필요"),

    /**
     * 계약금액이 기존 순결제금액보다 감소하여
     * 일부 결제금액의 환불 처리가 필요한 상태.
     */
    PARTIAL_REFUND_REQUIRED("부분 환불 필요"),

    /**
     * 참가 자체의 취소가 요청됐으며
     * 필요한 환불 등 후속 처리가 아직 완료되지 않은 상태.
     */
    CANCELLATION_PENDING("신청 취소·환불 처리 중"),

    /**
     * 참가 취소 및 필요한 후속 처리가 완료된 상태.
     */
    CANCELED("신청 취소 완료"),

    /**
     * 최초 결제가 완료되지 않은 상태로 유효기간이 종료된 신청.
     */
    EXPIRED("미결제 만료");

    private final String displayName;

    /** 기존 상태 식별자에 대응하는 한글 표시명을 설정한다. */
    RegistrationStatus(String displayName) {
        // 표시명은 저장 식별자인 enum 이름과 별도로 보관한다.
        this.displayName = displayName;
    }

    /** 운영 화면과 엑셀에서 사용할 한글 표시명을 반환한다. */
    public String getDisplayName() {
        // 호출한 출력 경로에만 한글 표시명을 제공한다.
        return displayName;
    }
}
