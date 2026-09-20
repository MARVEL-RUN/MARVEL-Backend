package kr.co.teambrain.marvelrun.user.common.exception.in_service;

import org.hibernate.exception.ConstraintViolationException;

/** 활성 신청 고유 제약 충돌만 구별한다. DB 메시지의 참가자 값은 읽거나 응답에 노출하지 않는다. */
public final class RegistrationUniqueConstraint {
    public static final String NAME = "uk_registration_active_unique_info";

    /** 정적 제약 식별 도구의 인스턴스 생성을 막는다. */
    private RegistrationUniqueConstraint() { }

    /** Hibernate가 추출한 제약 이름만 정확히 비교하고 다른 무결성 오류는 그대로 구별한다. */
    public static boolean matches(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ConstraintViolationException violation) {
                String name = violation.getConstraintName();
                if (name != null && (name.equals(NAME) || name.endsWith("." + NAME))) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
