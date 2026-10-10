package kr.co.teambrain.marvelrun.user.event.policy;

import kr.co.teambrain.marvelrun.common.inheritance_enum.EventStatus;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyModels.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 전역 기간·외부결제·신청일 정책을 동일 입력으로 판정한다. 조회·현재시각 취득·예외 반환은 하지 않는다. */
public final class RegistrationActionPolicyEvaluator {

    /** 전역 사유를 먼저 보존하고 해당 작업의 활성 구간 정책을 추가한다. */
    public Decision evaluateRegistrationActionPolicy(EventInput event, ParticipantInput participant, Action action,
            LocalDateTime now, List<Policy> policies) {
        List<Reason> reasons = new ArrayList<>();
        if (event == null || action == null || now == null || policies == null) {
            return new Decision(false, List.of(Reason.CONFIGURATION_INVALID));
        }

        // 최초 미결제 취소는 기간만 면제하며 외부결제 제한은 유지한다.
        boolean unpaidCancellation = action == Action.REFUND && participant != null && participant.initiallyUnpaid();
        if (!unpaidCancellation) {
            if (action == Action.PAYMENT) {
                if (event.paymentDeadline() != null && !now.isBefore(event.paymentDeadline())) {
                    reasons.add(Reason.PAYMENT_CLOSED);
                }
            } else {
                if (event.status() != EventStatus.OPEN
                        && !(action == Action.REFUND && event.status() == EventStatus.CLOSED)) {
                    reasons.add(Reason.EVENT_NOT_OPEN);
                }
                if (event.registrationStart() == null || (event.registrationDeadline() != null
                        && !event.registrationStart().isBefore(event.registrationDeadline()))) {
                    reasons.add(Reason.CONFIGURATION_INVALID);
                } else {
                    if (now.isBefore(event.registrationStart())) { reasons.add(Reason.REGISTRATION_NOT_STARTED); }
                    if (event.registrationDeadline() != null && !now.isBefore(event.registrationDeadline())) {
                        reasons.add(Reason.REGISTRATION_CLOSED);
                    }
                }
            }
        }

        // 신규 인원은 아직 신청일 정책 대상이 아니며 개별 대상의 작업 제한만 평가한다.
        if (participant != null && action != Action.ADD_MEMBER) {
            if (participant.externalPayment()) { reasons.add(Reason.EXTERNAL_PAYMENT); }
            boolean exempt = participant.initiallyUnpaid() && (action == Action.MODIFY || action == Action.REFUND);
            if (!exempt) {
                if (participant.registrationDate() == null) {
                    reasons.add(Reason.CONFIGURATION_INVALID);
                } else {
                    for (Policy policy : policies) {
                        if (!policy.enabled() || policy.action() != action
                                || !Objects.equals(event.eventId(), policy.eventId())) { continue; }
                        if (policy.registrationStart() == null || policy.registrationEnd() == null
                                || policy.effectiveFrom() == null) {
                            reasons.add(Reason.CONFIGURATION_INVALID);
                            continue;
                        }
                        if (!now.isBefore(policy.effectiveFrom())
                                && !participant.registrationDate().isBefore(policy.registrationStart())
                                && participant.registrationDate().isBefore(policy.registrationEnd())) {
                            reasons.add(switch (action) {
                                case MODIFY -> Reason.REGISTRATION_PERIOD_MODIFY;
                                case REFUND -> Reason.REGISTRATION_PERIOD_REFUND;
                                case PAYMENT -> Reason.REGISTRATION_PERIOD_PAYMENT;
                                default -> Reason.CONFIGURATION_INVALID;
                            });
                        }
                    }
                }
            }
        }
        List<Reason> distinct = reasons.stream().distinct().toList();
        return new Decision(distinct.isEmpty(), distinct);
    }

    /** 상세 응답에서도 명령 검증과 동일한 수정·환불 판정을 사용한다. */
    public UserPolicy evaluateRegistrationUserPolicy(EventInput event, ParticipantInput participant,
            LocalDateTime now, List<Policy> policies) {
        Decision modify = evaluateRegistrationActionPolicy(event, participant, Action.MODIFY, now, policies);
        Decision refund = evaluateRegistrationActionPolicy(event, participant, Action.REFUND, now, policies);
        return new UserPolicy(modify.allowed(), refund.allowed(), modify.reasons(), refund.reasons());
    }

    /** 공통정보 변경은 모든 구성원의 수정 허용을 요구하고 신규 추가는 전역 조건만 사용한다. */
    public OrganizationPolicy evaluateOrganizationUserPolicy(EventInput event, List<ParticipantInput> participants,
            LocalDateTime now, List<Policy> policies) {
        List<UserPolicy> members = participants.stream().map(p -> evaluateRegistrationUserPolicy(event, p, now, policies)).toList();
        List<Reason> modify = members.stream().flatMap(p -> p.modifyReasons().stream()).distinct().toList();
        List<Reason> refund = members.stream().flatMap(p -> p.refundReasons().stream()).distinct().toList();
        Decision add = evaluateRegistrationActionPolicy(event, null, Action.ADD_MEMBER, now, policies);
        return new OrganizationPolicy(modify.isEmpty(), refund.isEmpty(), modify, refund, add.allowed(), add.reasons());
    }
}

