package kr.co.teambrain.marvelrun.user.event.policy;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyModels.*;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationActionPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;

/** 요청별 정책 조회와 순수 판정·사용자 업무 예외의 연결을 담당한다. */
@Service
@RequiredArgsConstructor
public class RegistrationActionPolicyService {
    private final RegistrationActionPolicyRepository repository;
    private final RegistrationActionPolicyEvaluator evaluator = new RegistrationActionPolicyEvaluator();

    /** 인증 후 한 번 조회하여 같은 요청의 모든 검증에 전달한다. */
    public List<Policy> loadEnabledRegistrationActionPolicies(String eventId) {
        // 엔티티 조회 결과를 순수 판정 모델로 변환하여 요청 내에서 재사용한다.
        return repository.findAllByEventIdAndEnabledTrueOrderByActionTypeAscRegistrationStartAtAscIdAsc(eventId)
                .stream().map(this::toRegistrationActionPolicyModel).toList();
    }

    /** 영속성 구조와 서버 의존성이 없는 판정 입력을 분리한다. */
    private Policy toRegistrationActionPolicyModel(RegistrationActionPolicy policy) {
        return new Policy(policy.getId(), policy.getEventId(), Action.valueOf(policy.getActionType()),
                policy.getRegistrationStartAt(), policy.getRegistrationEndAt(),
                policy.getEffectiveFrom(), policy.isEnabled());
    }

    /** 신청 엔티티에서 판정에 필요한 값만 추출한다. */
    public static ParticipantInput toParticipantActionPolicyInput(Registration registration) {
        boolean unpaid = registration.getPaidAmount() != null && registration.getPaidAmount().signum() == 0
                && (registration.getStatus() == RegistrationStatus.PAYMENT_PENDING
                    || registration.getStatus() == RegistrationStatus.EXPIRED);
        return new ParticipantInput(registration.getRegistrationDate(), unpaid, registration.isExternalPayment());
    }

    /** 전역 판정에 필요한 대회 값만 추출한다. */
    public static EventInput toEventActionPolicyInput(Event event) {
        return new EventInput(event.getId(), event.getEventStatus(), event.getRegistStartDate(),
                event.getRegistDeadline(), event.getPaymentDeadline());
    }

    /** 신규 신청과 기존 기간 검증을 공통 판정 및 업무 예외 변환에 연결한다. */
    public static void validateGlobalRegistrationActionPolicy(Event event, Action action, LocalDateTime now) {
        Decision decision = new RegistrationActionPolicyEvaluator().evaluateRegistrationActionPolicy(
                event == null ? null : toEventActionPolicyInput(event), null, action, now, List.of());
        requireRegistrationActionAllowed(decision, action);
    }

    /** 호출부가 조회한 목록을 재사용하여 실제 작업만 검증한다. */
    public void validateRegistrationActionPolicy(Event event, Registration registration, Action action,
            LocalDateTime now, List<Policy> policies) {
        Decision decision = evaluator.evaluateRegistrationActionPolicy(toEventActionPolicyInput(event), toParticipantActionPolicyInput(registration), action, now, policies);
        requireRegistrationActionAllowed(decision, action);
    }

    /** 정책 결과를 명시적인 상세 응답 값으로 반환한다. */
    public UserPolicy evaluateRegistrationUserPolicy(Event event, Registration registration, LocalDateTime now, List<Policy> policies) {
        return evaluator.evaluateRegistrationUserPolicy(toEventActionPolicyInput(event), toParticipantActionPolicyInput(registration), now, policies);
    }

    /** 전역 사유 우선순위를 유지하며 기존 예외 체계로 변환한다. */
    public static void requireRegistrationActionAllowed(Decision decision, Action action) {
        if (decision.allowed()) { return; }
        Reason reason = decision.reasons().getFirst();
        ErrorCode code = switch (reason) {
            case CONFIGURATION_INVALID -> action == Action.PAYMENT
                    ? ErrorCode.PAYMENT_POLICY_CONFIGURATION_ERROR : ErrorCode.REGISTRATION_POLICY_CONFIGURATION_ERROR;
            case EVENT_NOT_OPEN -> ErrorCode.EVENT_NOT_OPEN;
            case REGISTRATION_NOT_STARTED -> ErrorCode.EVENT_REGISTRATION_NOT_STARTED;
            case REGISTRATION_CLOSED -> ErrorCode.EVENT_REGISTRATION_CLOSED;
            case PAYMENT_CLOSED -> ErrorCode.EVENT_PAYMENT_CLOSED;
            case EXTERNAL_PAYMENT -> ErrorCode.EXTERNAL_PAYMENT_REGISTRATION_RESTRICTED;
            case REGISTRATION_PERIOD_MODIFY -> ErrorCode.REGISTRATION_MODIFICATION_POLICY_BLOCKED;
            case REGISTRATION_PERIOD_REFUND -> ErrorCode.REGISTRATION_REFUND_POLICY_BLOCKED;
            case REGISTRATION_PERIOD_PAYMENT -> ErrorCode.REGISTRATION_PAYMENT_POLICY_BLOCKED;
        };
        throw new CustomException(code);
    }
}
