package kr.co.teambrain.marvelrun.user.common.exception.in_service;

import lombok.NoArgsConstructor;

import java.util.EnumSet;

@NoArgsConstructor
public final class ErrorExposurePolicy {

    // 외부로 노출하면 사용자 열거/정보누출 위험이 있는 민감 코드만 여기에 모읍니다.
    private static final EnumSet<ErrorCode> SENSITIVE = EnumSet.of(
            /* 대회 신청, 수정에 있어 브루트포스 힌트로 제공될 수 있는 에러들. */
//            ErrorCode.ALREADY_REGISTRATIONED_IN_EVENT,
            ErrorCode.USER_CANNOT_CHANGE_OWN_INFO_IN_REGISTRATION_PATCH
    );

    public static boolean isSensitive(ErrorCode code) {
        return SENSITIVE.contains(code);
    }
}