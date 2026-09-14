package kr.co.teambrain.marvelrun.user.common.exception;

import kr.co.teambrain.marvelrun.user.common.exception.out_service.MemberError;

import java.util.List;

/** 선검증 단계에서 수집한 모든 오류를 한 번에 던지는 예외. (런타임예외 = 기본 롤백 대상) */
public class BatchValidationException extends RuntimeException {
    private final List<MemberError> errors;

    public BatchValidationException(List<MemberError> errors) {
        super("배치 유효성 검증에 실패했습니다. 오류 목록을 확인해 주세요.");
        this.errors = errors;
    }

    public List<MemberError> getErrors() {
        return errors;
    }
}