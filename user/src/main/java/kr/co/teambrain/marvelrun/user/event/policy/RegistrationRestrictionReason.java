package kr.co.teambrain.marvelrun.user.event.policy;

/** 신규 접수의 전역·구간 제한 사유를 기존 신청 작업의 사유와 분리한다. */
public enum RegistrationRestrictionReason {
    CONFIGURATION_INVALID(10, "신청 가능 여부를 확인할 수 없습니다. 필요 시 관리자에게 문의해 주세요."),
    EVENT_NOT_OPEN(30, "현재 대회 상태에서는 신청할 수 없습니다."),
    REGISTRATION_NOT_STARTED(40, "신청 시작 전입니다."),
    REGISTRATION_CLOSED(50, "신청 기한이 마감되었습니다."),
    REGISTRATION_PERIOD_BLOCKED(60, "현재는 신청이 일시 중단된 기간입니다.");

    private final int priority;
    private final String message;

    /** 사유 우선순위와 기간을 제외한 기본 문구를 보존한다. */
    RegistrationRestrictionReason(int priority, String message) {
        this.priority = priority;
        this.message = message;
    }

    /** 숫자가 작을수록 먼저 안내한다. */
    public int priority() { return priority; }

    /** 동적 기간을 붙이기 전 기본 안내 문구다. */
    public String message() { return message; }
}
