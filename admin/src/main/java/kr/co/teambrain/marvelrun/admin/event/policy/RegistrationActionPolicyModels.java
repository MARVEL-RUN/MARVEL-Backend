package kr.co.teambrain.marvelrun.admin.event.policy;

import com.fasterxml.jackson.annotation.JsonInclude;

import kr.co.teambrain.marvelrun.common.inheritance_enum.EventStatus;
import kr.co.teambrain.marvelrun.admin.event.policy.RegistrationActionPolicyReasons.*;
import java.time.LocalDateTime;

/** 신청 정책의 내부 입력과 대상별 명시적인 API 응답을 정의한다. */
public final class RegistrationActionPolicyModels {
    /** 값 모델 이름 공간이며 인스턴스를 생성하지 않는다. */
    private RegistrationActionPolicyModels() { }

    /** DELETE_MEMBER는 취소 문맥이며 DB 정책 작업 REFUND를 사용한다. */
    public enum Action { ADD_MEMBER, MODIFY, REFUND, PAYMENT, DELETE_MEMBER }

    /** 내부 판정 근거이며 API에는 작업별 enum과 메시지로 변환해서 제공한다. */
    public enum Reason {
        CONFIGURATION_INVALID, EVENT_NOT_OPEN, REGISTRATION_NOT_STARTED,
        REGISTRATION_CLOSED, PAYMENT_CLOSED, EXTERNAL_PAYMENT,
        REGISTRATION_PERIOD_MODIFY, REGISTRATION_PERIOD_REFUND, REGISTRATION_PERIOD_PAYMENT
    }

    /** 한 요청이 공유하는 대회 전역 조건이다. */
    public record EventInput(String eventId, EventStatus status, LocalDateTime registrationStart,
            LocalDateTime registrationDeadline, LocalDateTime paymentDeadline) { }

    /** paymentTarget은 미납 대상 선정에만 쓰며 실제 결제 가능 상태를 의미하지 않는다. */
    public record ParticipantInput(LocalDateTime registrationDate, boolean initiallyUnpaid,
            boolean externalPayment, boolean paymentTarget) {
        /** 단일 작업 판정에는 단체 결제 대상 선정이 필요하지 않다. */
        public ParticipantInput(LocalDateTime registrationDate, boolean initiallyUnpaid, boolean externalPayment) {
            this(registrationDate, initiallyUnpaid, externalPayment, false);
        }
    }

    /** 신청일 대상 구간과 적용 개시를 분리한 정책 행이다. */
    public record Policy(String id, String eventId, Action action, LocalDateTime registrationStart,
            LocalDateTime registrationEnd, LocalDateTime effectiveFrom, boolean enabled) { }

    /** 내부 단일 대표 사유이며 null이면 해당 정책 검증을 통과한다. */
    public record Decision(Reason reason) {
        /** 금융·재고·동시성 검증과 별개의 정책상 허용 여부다. */
        public boolean allowed() { return reason == null; }
    }

    /** 개인 신청에는 실제 결제 완료 여부와 무관하게 세 작업의 정책을 제공한다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record UserPolicy(
            boolean modifyAllowed, ModificationRestrictionReasonResponse modifyReason,
            boolean paymentAllowed, PaymentRestrictionReasonResponse paymentReason,
            boolean refundAllowed, RefundRestrictionReasonResponse refundReason) { }

    /** 단체 전체 작업과 신규 인원 추가를 구성원 개인 작업에서 분리한다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OrganizationPolicy(
            boolean modifyAllowed, OrganizationModificationRestrictionReasonResponse modifyReason,
            boolean paymentAllowed, OrganizationPaymentRestrictionReasonResponse paymentReason,
            boolean refundAllowed, OrganizationRefundRestrictionReasonResponse refundReason,
            boolean addMemberAllowed, OrganizationAddMemberRestrictionReasonResponse addMemberReason) { }

    /** 구성원 삭제에는 필요 시 환불이 포함되며 결제·환불 필드를 별도로 노출하지 않는다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OrganizationMemberPolicy(
            boolean modifyAllowed, ModificationRestrictionReasonResponse modifyReason,
            boolean deleteMemberAllowed, DeleteMemberRestrictionReasonResponse deleteMemberReason) { }

    /** ModificationRestrictionReason의 코드와 바로 표시할 메시지를 함께 반환한다. */
    public record ModificationRestrictionReasonResponse(ModificationRestrictionReason code, String message) {
        /** 허용 결과는 null로, 제한 결과는 해당 작업 전용 사유로 변환한다. */
        public static ModificationRestrictionReasonResponse fromDecision(Decision decision) {
            if (decision.allowed()) { return null; }
            ModificationRestrictionReason code = ModificationRestrictionReason.valueOf(decision.reason().name());
            return new ModificationRestrictionReasonResponse(code, code.message());
        }
    }

    /** RefundRestrictionReason의 코드와 바로 표시할 메시지를 함께 반환한다. */
    public record RefundRestrictionReasonResponse(RefundRestrictionReason code, String message) {
        /** 허용 결과는 null로, 제한 결과는 해당 작업 전용 사유로 변환한다. */
        public static RefundRestrictionReasonResponse fromDecision(Decision decision) {
            if (decision.allowed()) { return null; }
            RefundRestrictionReason code = RefundRestrictionReason.valueOf(decision.reason().name());
            return new RefundRestrictionReasonResponse(code, code.message());
        }
    }

    /** PaymentRestrictionReason의 코드와 바로 표시할 메시지를 함께 반환한다. */
    public record PaymentRestrictionReasonResponse(PaymentRestrictionReason code, String message) {
        /** 허용 결과는 null로, 제한 결과는 해당 작업 전용 사유로 변환한다. */
        public static PaymentRestrictionReasonResponse fromDecision(Decision decision) {
            if (decision.allowed()) { return null; }
            PaymentRestrictionReason code = PaymentRestrictionReason.valueOf(decision.reason().name());
            return new PaymentRestrictionReasonResponse(code, code.message());
        }
    }

    /** DeleteMemberRestrictionReason의 코드와 바로 표시할 메시지를 함께 반환한다. */
    public record DeleteMemberRestrictionReasonResponse(DeleteMemberRestrictionReason code, String message) {
        /** 허용 결과는 null로, 제한 결과는 해당 작업 전용 사유로 변환한다. */
        public static DeleteMemberRestrictionReasonResponse fromDecision(Decision decision) {
            if (decision.allowed()) { return null; }
            DeleteMemberRestrictionReason code = DeleteMemberRestrictionReason.valueOf(decision.reason().name());
            return new DeleteMemberRestrictionReasonResponse(code, code.message());
        }
    }

    /** OrganizationModificationRestrictionReason의 코드와 바로 표시할 메시지를 함께 반환한다. */
    public record OrganizationModificationRestrictionReasonResponse(OrganizationModificationRestrictionReason code, String message) {
        /** 허용 결과는 null로, 제한 결과는 해당 작업 전용 사유로 변환한다. */
        public static OrganizationModificationRestrictionReasonResponse fromDecision(Decision decision) {
            if (decision.allowed()) { return null; }
            OrganizationModificationRestrictionReason code = OrganizationModificationRestrictionReason.valueOf(decision.reason().name());
            return new OrganizationModificationRestrictionReasonResponse(code, code.message());
        }
    }

    /** OrganizationPaymentRestrictionReason의 코드와 바로 표시할 메시지를 함께 반환한다. */
    public record OrganizationPaymentRestrictionReasonResponse(OrganizationPaymentRestrictionReason code, String message) {
        /** 허용 결과는 null로, 제한 결과는 해당 작업 전용 사유로 변환한다. */
        public static OrganizationPaymentRestrictionReasonResponse fromDecision(Decision decision) {
            if (decision.allowed()) { return null; }
            OrganizationPaymentRestrictionReason code = OrganizationPaymentRestrictionReason.valueOf(decision.reason().name());
            return new OrganizationPaymentRestrictionReasonResponse(code, code.message());
        }
    }

    /** OrganizationRefundRestrictionReason의 코드와 바로 표시할 메시지를 함께 반환한다. */
    public record OrganizationRefundRestrictionReasonResponse(OrganizationRefundRestrictionReason code, String message) {
        /** 허용 결과는 null로, 제한 결과는 해당 작업 전용 사유로 변환한다. */
        public static OrganizationRefundRestrictionReasonResponse fromDecision(Decision decision) {
            if (decision.allowed()) { return null; }
            OrganizationRefundRestrictionReason code = OrganizationRefundRestrictionReason.valueOf(decision.reason().name());
            return new OrganizationRefundRestrictionReasonResponse(code, code.message());
        }
    }

    /** OrganizationAddMemberRestrictionReason의 코드와 바로 표시할 메시지를 함께 반환한다. */
    public record OrganizationAddMemberRestrictionReasonResponse(OrganizationAddMemberRestrictionReason code, String message) {
        /** 허용 결과는 null로, 제한 결과는 해당 작업 전용 사유로 변환한다. */
        public static OrganizationAddMemberRestrictionReasonResponse fromDecision(Decision decision) {
            if (decision.allowed()) { return null; }
            OrganizationAddMemberRestrictionReason code = OrganizationAddMemberRestrictionReason.valueOf(decision.reason().name());
            return new OrganizationAddMemberRestrictionReasonResponse(code, code.message());
        }
    }
}
