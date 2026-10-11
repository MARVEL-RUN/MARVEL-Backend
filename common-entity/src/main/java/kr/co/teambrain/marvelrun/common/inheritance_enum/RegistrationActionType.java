package kr.co.teambrain.marvelrun.common.inheritance_enum;

/** 신청일 구간별 정책 테이블에 저장할 수 있는 작업 종류다. enum 이름을 VARCHAR로 저장한다. */
public enum RegistrationActionType {
    /** 신청 정보 수정을 제한한다. */
    MODIFY,
    /** 환불을 제한하며 환불을 수반하는 구성원 삭제에도 적용한다. */
    REFUND,
    /** 최초·추가·혼합 결제를 제한한다. */
    PAYMENT
}
