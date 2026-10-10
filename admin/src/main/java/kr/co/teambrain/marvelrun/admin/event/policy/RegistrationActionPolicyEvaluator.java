package kr.co.teambrain.marvelrun.admin.event.policy;

import kr.co.teambrain.marvelrun.common.inheritance_enum.EventStatus;
import kr.co.teambrain.marvelrun.admin.event.policy.RegistrationActionPolicyModels.*;
import kr.co.teambrain.marvelrun.admin.event.policy.RegistrationActionPolicyReasons.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 전역·신청일·외부결제 정책을 판정하고 작업별 우선순위로 대표 사유를 선택한다. */
public final class RegistrationActionPolicyEvaluator {

    /** 실제 결제 상태 검증과 독립적으로 지정된 작업의 정책을 평가한다. */
    public Decision evaluateRegistrationActionPolicy(EventInput event, ParticipantInput participant, Action action,
            LocalDateTime now, List<Policy> policies) {
        return evaluateRegistrationActionPolicy(event, participant, action, now, policies, false);
    }

    /** 개인/단체 각각의 enum 우선순위를 모든 후보 사유에 적용한다. */
    private Decision evaluateRegistrationActionPolicy(EventInput event, ParticipantInput participant, Action action,
            LocalDateTime now, List<Policy> policies, boolean organizationScope) {
        // 서버 입력 누락은 DB 컬럼의 NOT NULL 보장과 별개다.
        if (event == null || action == null || now == null || policies == null) {
            return new Decision(Reason.CONFIGURATION_INVALID);
        }
        List<Reason> reasons = new ArrayList<>();
        boolean unpaidCancellation = action == Action.DELETE_MEMBER && participant != null && participant.initiallyUnpaid();
        Action policyAction = action == Action.DELETE_MEMBER ? Action.REFUND : action;

        // 환불 없는 취소만 기간을 면제하고 외부결제 제한은 아래에서 유지한다.
        if (!unpaidCancellation) {
            if (policyAction == Action.PAYMENT) {
                if (event.paymentDeadline() != null && !now.isBefore(event.paymentDeadline())) {
                    reasons.add(Reason.PAYMENT_CLOSED);
                }
            } else {
                if (event.status() != EventStatus.OPEN
                        && !(policyAction == Action.REFUND && event.status() == EventStatus.CLOSED)) {
                    reasons.add(Reason.EVENT_NOT_OPEN);
                }
                if (event.registrationDeadline() != null
                        && !event.registrationStart().isBefore(event.registrationDeadline())) {
                    reasons.add(Reason.CONFIGURATION_INVALID);
                } else {
                    if (now.isBefore(event.registrationStart())) { reasons.add(Reason.REGISTRATION_NOT_STARTED); }
                    if (event.registrationDeadline() != null && !now.isBefore(event.registrationDeadline())) {
                        reasons.add(Reason.REGISTRATION_CLOSED);
                    }
                }
            }
        }

        // 필수 날짜는 DB에서 전달받으며 정책 입력 유효성 검사를 매 요청에 반복하지 않는다.
        if (participant != null && action != Action.ADD_MEMBER) {
            if (participant.externalPayment()) { reasons.add(Reason.EXTERNAL_PAYMENT); }
            boolean exempt = unpaidCancellation || (participant.initiallyUnpaid() && action == Action.MODIFY);
            if (!exempt) {
                for (Policy policy : policies) {
                    if (!policy.enabled() || policy.action() != policyAction
                            || !Objects.equals(event.eventId(), policy.eventId())) { continue; }
                    if (!now.isBefore(policy.effectiveFrom())
                            && !participant.registrationDate().isBefore(policy.registrationStart())
                            && participant.registrationDate().isBefore(policy.registrationEnd())) {
                        reasons.add(switch (policyAction) {
                            case MODIFY -> Reason.REGISTRATION_PERIOD_MODIFY;
                            case REFUND -> Reason.REGISTRATION_PERIOD_REFUND;
                            case PAYMENT -> Reason.REGISTRATION_PERIOD_PAYMENT;
                            default -> Reason.CONFIGURATION_INVALID;
                        });
                    }
                }
            }
        }

        // enum 선언 및 검증 순서에 의존하지 않고 작업별 우선순위를 적용한다.
        return new Decision(reasons.stream()
                .min(Comparator.comparingInt(reason -> organizationScope
                        ? organizationReasonPriority(action, reason) : registrationReasonPriority(action, reason))).orElse(null));
    }

    /** 명령 검증에서도 구성원 목록 순서와 무관한 동일 대표 사유를 사용한다. */
    public Decision evaluateRegistrationActionsPolicy(EventInput event, List<ParticipantInput> participants,
            Action action, LocalDateTime now, List<Policy> policies, boolean organizationScope) {
        List<Decision> decisions = participants.stream()
                .map(participant -> evaluateRegistrationActionPolicy(event, participant, action, now, policies, organizationScope)).toList();
        if (decisions.isEmpty()) {
            return evaluateRegistrationActionPolicy(event, null, action, now, policies);
        }
        return new Decision(decisions.stream().map(Decision::reason).filter(Objects::nonNull)
                .min(Comparator.comparingInt(reason -> organizationScope
                        ? organizationReasonPriority(action, reason) : registrationReasonPriority(action, reason)))
                .orElse(null));
    }

    /** 개인 상세는 수정·결제·환불 각각의 정책상 허용 여부를 제공한다. */
    public UserPolicy evaluateRegistrationUserPolicy(EventInput event, ParticipantInput participant,
            LocalDateTime now, List<Policy> policies) {
        Decision modify = evaluateRegistrationActionPolicy(event, participant, Action.MODIFY, now, policies);
        Decision payment = evaluateRegistrationActionPolicy(event, participant, Action.PAYMENT, now, policies);
        Decision refund = evaluateRegistrationActionPolicy(event, participant, Action.REFUND, now, policies);
        return new UserPolicy(modify.allowed(), ModificationRestrictionReasonResponse.fromDecision(modify),
                payment.allowed(), PaymentRestrictionReasonResponse.fromDecision(payment),
                refund.allowed(), RefundRestrictionReasonResponse.fromDecision(refund));
    }

    /** 구성원 삭제는 환불을 포함하며 최초 미결제 삭제에는 환불 기간을 적용하지 않는다. */
    public OrganizationMemberPolicy evaluateOrganizationMemberPolicy(EventInput event, ParticipantInput participant,
            LocalDateTime now, List<Policy> policies) {
        Decision modify = evaluateRegistrationActionPolicy(event, participant, Action.MODIFY, now, policies);
        Decision delete = evaluateRegistrationActionPolicy(event, participant, Action.DELETE_MEMBER, now, policies);
        return new OrganizationMemberPolicy(modify.allowed(), ModificationRestrictionReasonResponse.fromDecision(modify),
                delete.allowed(), DeleteMemberRestrictionReasonResponse.fromDecision(delete));
    }

    /** 단체 결제는 미납 귀속만 집계하고 공통정보·전체 취소·신규 추가를 독립적으로 평가한다. */
    public OrganizationPolicy evaluateOrganizationUserPolicy(EventInput event, List<ParticipantInput> participants,
            LocalDateTime now, List<Policy> policies) {
        // 완납 구성원의 결제 정책을 다른 구성원의 미납 결제에 전파하지 않는다.
        Decision modify = evaluateRegistrationActionsPolicy(event, participants, Action.MODIFY, now, policies, true);
        Decision payment = evaluateRegistrationActionsPolicy(event,
                participants.stream().filter(ParticipantInput::paymentTarget).toList(), Action.PAYMENT, now, policies, true);
        Decision refund = evaluateRegistrationActionsPolicy(event, participants, Action.DELETE_MEMBER, now, policies, true);
        Decision add = evaluateRegistrationActionPolicy(event, null, Action.ADD_MEMBER, now, policies);
        return new OrganizationPolicy(
                modify.allowed(), OrganizationModificationRestrictionReasonResponse.fromDecision(modify),
                payment.allowed(), OrganizationPaymentRestrictionReasonResponse.fromDecision(payment),
                refund.allowed(), OrganizationRefundRestrictionReasonResponse.fromDecision(refund),
                add.allowed(), OrganizationAddMemberRestrictionReasonResponse.fromDecision(add));
    }

    /** 개인·구성원 작업 enum에 정의한 우선순위를 내부 근거에 연결한다. */
    private int registrationReasonPriority(Action action, Reason reason) {
        return switch (action) {
            case MODIFY -> ModificationRestrictionReason.valueOf(reason.name()).priority();
            case REFUND -> RefundRestrictionReason.valueOf(reason.name()).priority();
            case PAYMENT -> PaymentRestrictionReason.valueOf(reason.name()).priority();
            case DELETE_MEMBER -> DeleteMemberRestrictionReason.valueOf(reason.name()).priority();
            case ADD_MEMBER -> OrganizationAddMemberRestrictionReason.valueOf(reason.name()).priority();
        };
    }

    /** 단체 작업은 해당 작업 전용 enum의 우선순위로 구성원 사유를 비교한다. */
    private int organizationReasonPriority(Action action, Reason reason) {
        return switch (action) {
            case MODIFY -> OrganizationModificationRestrictionReason.valueOf(reason.name()).priority();
            case REFUND, DELETE_MEMBER -> OrganizationRefundRestrictionReason.valueOf(reason.name()).priority();
            case PAYMENT -> OrganizationPaymentRestrictionReason.valueOf(reason.name()).priority();
            case ADD_MEMBER -> OrganizationAddMemberRestrictionReason.valueOf(reason.name()).priority();
        };
    }
}
