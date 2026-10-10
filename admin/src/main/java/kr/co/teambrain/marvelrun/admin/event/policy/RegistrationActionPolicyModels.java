package kr.co.teambrain.marvelrun.admin.event.policy;

import kr.co.teambrain.marvelrun.common.inheritance_enum.EventStatus;
import java.time.LocalDateTime;
import java.util.List;

/** 서버 의존성 없이 신청 작업 제한의 입력·정책·결과를 전달한다. */
public final class RegistrationActionPolicyModels {
    private RegistrationActionPolicyModels() { }

    /** 신규 인원 추가는 전역 접수 기한만 사용한다. */
    public enum Action { ADD_MEMBER, MODIFY, REFUND, PAYMENT }

    /** 사용자에게 전달할 제한 원인을 작업 결과에 함께 보존한다. */
    public enum Reason {
        CONFIGURATION_INVALID, EVENT_NOT_OPEN, REGISTRATION_NOT_STARTED,
        REGISTRATION_CLOSED, PAYMENT_CLOSED, EXTERNAL_PAYMENT,
        REGISTRATION_PERIOD_MODIFY, REGISTRATION_PERIOD_REFUND, REGISTRATION_PERIOD_PAYMENT
    }

    /** 한 요청에서 공유하는 대회와 기준시각이다. */
    public record EventInput(String eventId, EventStatus status, LocalDateTime registrationStart,
            LocalDateTime registrationDeadline, LocalDateTime paymentDeadline) { }

    /** 최초 미결제 여부는 금액만으로 추정하지 않고 호출부가 상태와 함께 판별한다. */
    public record ParticipantInput(LocalDateTime registrationDate, boolean initiallyUnpaid,
            boolean externalPayment) { }

    /** 대상 신청일 구간과 적용 시작시각이 분리된 정책 행이다. */
    public record Policy(String id, String eventId, Action action, LocalDateTime registrationStart,
            LocalDateTime registrationEnd, LocalDateTime effectiveFrom, boolean enabled) { }

    /** 금융·재고 검증과 독립적인 정책상 허용 결과다. */
    public record Decision(boolean allowed, List<Reason> reasons) {
        public Decision { reasons = List.copyOf(reasons); }
    }

    /** 개인 또는 구성원의 상세 응답 계약이다. */
    public record UserPolicy(boolean modifyAllowed, boolean refundAllowed,
            List<Reason> modifyReasons, List<Reason> refundReasons) {
        public UserPolicy {
            modifyReasons = List.copyOf(modifyReasons);
            refundReasons = List.copyOf(refundReasons);
        }
    }

    /** 단체 공통정보 변경과 인원 추가 가능 여부를 분리한다. */
    public record OrganizationPolicy(boolean modifyAllowed, boolean refundAllowed,
            List<Reason> modifyReasons, List<Reason> refundReasons,
            boolean addMemberAllowed, List<Reason> addMemberReasons) {
        public OrganizationPolicy {
            modifyReasons = List.copyOf(modifyReasons);
            refundReasons = List.copyOf(refundReasons);
            addMemberReasons = List.copyOf(addMemberReasons);
        }
    }
}

