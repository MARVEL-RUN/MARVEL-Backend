package kr.co.teambrain.marvelrun.user.event.policy;

/** 작업별 사용자 안내 문구와 대표 제한 사유의 표기 우선순위를 정의한다. */
public final class RegistrationActionPolicyReasons {
    /** enum 이름 공간이며 인스턴스를 생성하지 않는다. */
    private RegistrationActionPolicyReasons() { }

    /** 개인 또는 구성원 작업의 제한 사유이며 숫자가 작을수록 먼저 안내한다. */
    public enum ModificationRestrictionReason {
        CONFIGURATION_INVALID(10, "신청 수정 가능 여부를 확인할 수 없습니다. 필요 시 관리자에게 문의해 주세요."),
        EXTERNAL_PAYMENT(20, "외부결제 신청은 직접 수정할 수 없습니다. 필요 시 관리자에게 문의해 주세요."),
        EVENT_NOT_OPEN(30, "현재 대회 상태에서는 신청 정보를 수정할 수 없습니다."),
        REGISTRATION_NOT_STARTED(40, "신청 시작 전에는 신청 정보를 수정할 수 없습니다."),
        REGISTRATION_CLOSED(50, "신청 정보 수정 기한이 마감되었습니다."),
        REGISTRATION_PERIOD_MODIFY(60, "배송을 위한 신청 내역 확정에 따라 수정할 수 없습니다. 필요 시 관리자에게 문의해 주세요.");

        private final int priority;
        private final String message;

        /** 코드와 표시 우선순위·문구를 함께 보존한다. */
        ModificationRestrictionReason(int priority, String message) {
            this.priority = priority;
            this.message = message;
        }

        /** 대표 사유 선정에 사용할 명시적 우선순위다. */
        public int priority() { return priority; }

        /** 프론트가 별도 번역 없이 표시할 안내 문구다. */
        public String message() { return message; }
    }

    /** 개인 또는 구성원 작업의 제한 사유이며 숫자가 작을수록 먼저 안내한다. */
    public enum RefundRestrictionReason {
        CONFIGURATION_INVALID(10, "환불 가능 여부를 확인할 수 없습니다. 필요 시 관리자에게 문의해 주세요."),
        EXTERNAL_PAYMENT(20, "외부결제 신청은 직접 환불할 수 없습니다. 필요 시 관리자에게 문의해 주세요."),
        EVENT_NOT_OPEN(30, "현재 대회 상태에서는 환불을 신청할 수 없습니다."),
        REGISTRATION_NOT_STARTED(40, "신청 시작 전에는 환불을 신청할 수 없습니다."),
        REGISTRATION_CLOSED(50, "환불 신청 기한이 마감되었습니다."),
        REGISTRATION_PERIOD_REFUND(60, "배송을 위한 신청 내역 확정에 따라 환불할 수 없습니다. 필요 시 관리자에게 문의해 주세요.");

        private final int priority;
        private final String message;

        /** 코드와 표시 우선순위·문구를 함께 보존한다. */
        RefundRestrictionReason(int priority, String message) {
            this.priority = priority;
            this.message = message;
        }

        /** 대표 사유 선정에 사용할 명시적 우선순위다. */
        public int priority() { return priority; }

        /** 프론트가 별도 번역 없이 표시할 안내 문구다. */
        public String message() { return message; }
    }

    /** 개인 또는 구성원 작업의 제한 사유이며 숫자가 작을수록 먼저 안내한다. */
    public enum PaymentRestrictionReason {
        CONFIGURATION_INVALID(10, "결제 가능 여부를 확인할 수 없습니다. 필요 시 관리자에게 문의해 주세요."),
        EXTERNAL_PAYMENT(20, "외부결제 신청은 온라인 결제를 진행할 수 없습니다. 필요 시 관리자에게 문의해 주세요."),
        PAYMENT_CLOSED(50, "결제 기한이 마감되었습니다."),
        REGISTRATION_PERIOD_PAYMENT(60, "배송을 위한 신청 내역 확정에 따라 결제할 수 없습니다. 필요 시 관리자에게 문의해 주세요.");

        private final int priority;
        private final String message;

        /** 코드와 표시 우선순위·문구를 함께 보존한다. */
        PaymentRestrictionReason(int priority, String message) {
            this.priority = priority;
            this.message = message;
        }

        /** 대표 사유 선정에 사용할 명시적 우선순위다. */
        public int priority() { return priority; }

        /** 프론트가 별도 번역 없이 표시할 안내 문구다. */
        public String message() { return message; }
    }

    /** 개인 또는 구성원 작업의 제한 사유이며 숫자가 작을수록 먼저 안내한다. */
    public enum DeleteMemberRestrictionReason {
        CONFIGURATION_INVALID(10, "구성원 삭제 가능 여부를 확인할 수 없습니다. 필요 시 관리자에게 문의해 주세요."),
        EXTERNAL_PAYMENT(20, "외부결제 구성원은 직접 삭제할 수 없습니다. 필요 시 관리자에게 문의해 주세요."),
        EVENT_NOT_OPEN(30, "현재 대회 상태에서는 환불이 필요한 구성원을 삭제할 수 없습니다."),
        REGISTRATION_NOT_STARTED(40, "신청 시작 전에는 환불이 필요한 구성원을 삭제할 수 없습니다."),
        REGISTRATION_CLOSED(50, "환불 신청 기한이 마감되어 결제한 구성원을 삭제할 수 없습니다."),
        REGISTRATION_PERIOD_REFUND(60, "배송을 위한 신청 내역 확정에 따라 구성원을 삭제할 수 없습니다. 필요 시 관리자에게 문의해 주세요.");

        private final int priority;
        private final String message;

        /** 코드와 표시 우선순위·문구를 함께 보존한다. */
        DeleteMemberRestrictionReason(int priority, String message) {
            this.priority = priority;
            this.message = message;
        }

        /** 대표 사유 선정에 사용할 명시적 우선순위다. */
        public int priority() { return priority; }

        /** 프론트가 별도 번역 없이 표시할 안내 문구다. */
        public String message() { return message; }
    }

    /** 단체 전체 작업의 제한 사유이며 숫자가 작을수록 먼저 안내한다. */
    public enum OrganizationModificationRestrictionReason {
        CONFIGURATION_INVALID(10, "단체 정보 수정 가능 여부를 확인할 수 없습니다. 필요 시 관리자에게 문의해 주세요."),
        EXTERNAL_PAYMENT(20, "외부결제 신청이 포함되어 단체 정보를 직접 수정할 수 없습니다. 필요 시 관리자에게 문의해 주세요."),
        EVENT_NOT_OPEN(30, "현재 대회 상태에서는 단체 정보를 수정할 수 없습니다."),
        REGISTRATION_NOT_STARTED(40, "신청 시작 전에는 단체 정보를 수정할 수 없습니다."),
        REGISTRATION_CLOSED(50, "단체 정보 수정 기한이 마감되었습니다."),
        REGISTRATION_PERIOD_MODIFY(60, "배송을 위한 신청 내역 확정에 따라 단체 정보를 수정할 수 없습니다. 필요 시 관리자에게 문의해 주세요.");

        private final int priority;
        private final String message;

        /** 코드와 표시 우선순위·문구를 함께 보존한다. */
        OrganizationModificationRestrictionReason(int priority, String message) {
            this.priority = priority;
            this.message = message;
        }

        /** 대표 사유 선정에 사용할 명시적 우선순위다. */
        public int priority() { return priority; }

        /** 프론트가 별도 번역 없이 표시할 안내 문구다. */
        public String message() { return message; }
    }

    /** 단체 전체 작업의 제한 사유이며 숫자가 작을수록 먼저 안내한다. */
    public enum OrganizationPaymentRestrictionReason {
        CONFIGURATION_INVALID(10, "단체 결제 가능 여부를 확인할 수 없습니다. 필요 시 관리자에게 문의해 주세요."),
        EXTERNAL_PAYMENT(20, "결제 대상에 외부결제 신청이 포함되어 단체 결제를 진행할 수 없습니다. 필요 시 관리자에게 문의해 주세요."),
        PAYMENT_CLOSED(50, "단체 결제 기한이 마감되었습니다."),
        REGISTRATION_PERIOD_PAYMENT(60, "배송을 위한 신청 내역 확정에 따라 단체 결제를 진행할 수 없습니다. 필요 시 관리자에게 문의해 주세요.");

        private final int priority;
        private final String message;

        /** 코드와 표시 우선순위·문구를 함께 보존한다. */
        OrganizationPaymentRestrictionReason(int priority, String message) {
            this.priority = priority;
            this.message = message;
        }

        /** 대표 사유 선정에 사용할 명시적 우선순위다. */
        public int priority() { return priority; }

        /** 프론트가 별도 번역 없이 표시할 안내 문구다. */
        public String message() { return message; }
    }

    /** 단체 전체 작업의 제한 사유이며 숫자가 작을수록 먼저 안내한다. */
    public enum OrganizationRefundRestrictionReason {
        CONFIGURATION_INVALID(10, "단체 전체 환불·취소 가능 여부를 확인할 수 없습니다. 필요 시 관리자에게 문의해 주세요."),
        EXTERNAL_PAYMENT(20, "외부결제 신청이 포함되어 단체 전체 환불·취소를 직접 진행할 수 없습니다. 필요 시 관리자에게 문의해 주세요."),
        EVENT_NOT_OPEN(30, "현재 대회 상태에서는 단체 전체 환불·취소를 진행할 수 없습니다."),
        REGISTRATION_NOT_STARTED(40, "신청 시작 전에는 단체 전체 환불·취소를 진행할 수 없습니다."),
        REGISTRATION_CLOSED(50, "환불 신청 기한이 마감되어 단체 전체 환불·취소를 진행할 수 없습니다."),
        REGISTRATION_PERIOD_REFUND(60, "배송을 위한 신청 내역 확정에 따라 단체 전체 환불·취소를 진행할 수 없습니다. 필요 시 관리자에게 문의해 주세요.");

        private final int priority;
        private final String message;

        /** 코드와 표시 우선순위·문구를 함께 보존한다. */
        OrganizationRefundRestrictionReason(int priority, String message) {
            this.priority = priority;
            this.message = message;
        }

        /** 대표 사유 선정에 사용할 명시적 우선순위다. */
        public int priority() { return priority; }

        /** 프론트가 별도 번역 없이 표시할 안내 문구다. */
        public String message() { return message; }
    }

    /** 단체 전체 작업의 제한 사유이며 숫자가 작을수록 먼저 안내한다. */
    public enum OrganizationAddMemberRestrictionReason {
        CONFIGURATION_INVALID(10, "구성원 추가 가능 여부를 확인할 수 없습니다. 필요 시 관리자에게 문의해 주세요."),
        EVENT_NOT_OPEN(30, "현재 대회 상태에서는 구성원을 추가할 수 없습니다."),
        REGISTRATION_NOT_STARTED(40, "신청 시작 전에는 구성원을 추가할 수 없습니다."),
        REGISTRATION_CLOSED(50, "신청 기한이 마감되어 구성원을 추가할 수 없습니다.");

        private final int priority;
        private final String message;

        /** 코드와 표시 우선순위·문구를 함께 보존한다. */
        OrganizationAddMemberRestrictionReason(int priority, String message) {
            this.priority = priority;
            this.message = message;
        }

        /** 대표 사유 선정에 사용할 명시적 우선순위다. */
        public int priority() { return priority; }

        /** 프론트가 별도 번역 없이 표시할 안내 문구다. */
        public String message() { return message; }
    }
}

