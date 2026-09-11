package kr.co.teambrain.marvelrun.common.inheritance_enum.phone_auth_policy;

/** 전화번호 인증여부 수정 시도시 특정 대상 vs 가용 가능 전체 대상 방식 */
public enum PhoneAuthBulkTargetScope {
    SELECTED,
    ALL_ELIGIBLE
}