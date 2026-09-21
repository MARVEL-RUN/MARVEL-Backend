package kr.co.teambrain.marvelrun.user.event.command.application.support;

import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;

/** 모든 신청 행의 기본 false를 허용하며 저장된 true의 명시적 철회만 차단한다. */
public final class GuardianConsentSupport {
    /** 정적 정책 유틸리티이다. */
    private GuardianConsentSupport() { }

    /** null은 기존값 유지, false는 미동의 유지, true는 최초 또는 기존 동의이다. */
    public static boolean resolve(boolean stored, Boolean requested) {
        if (stored && Boolean.FALSE.equals(requested)) {
            throw new CustomException(ErrorCode.GUARDIAN_CONSENT_REQUIRED);
        }
        return requested == null ? stored : requested;
    }
}
