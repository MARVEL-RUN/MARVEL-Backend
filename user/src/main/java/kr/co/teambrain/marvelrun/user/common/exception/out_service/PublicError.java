package kr.co.teambrain.marvelrun.user.common.exception.out_service;


/** ErrorCode의 노출 자체가 보안 취약점이 될 수 있는 경우, 이를 대체해서 전달하는 Error.
 * policy.ErrorExposurePolicy에서 표기된 내역대로만 사용. */
public enum PublicError {
    REQUEST_DENIED("요청을 처리할 수 없습니다. 백엔드 개발자 문의 요망(추후 본 문장내역 변경)"),
    DATABASE_ERROR("수행할 수 없습니다.");
    private final String message;
    PublicError(String message) { this.message = message; }
    public String getMessage() { return message; }
}