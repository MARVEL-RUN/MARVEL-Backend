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
import java.util.Collection;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationActionType;

/** 요청별 정책 조회와 순수 판정·사용자 업무 예외의 연결을 담당한다. */
@Service
@RequiredArgsConstructor
public class RegistrationActionPolicyService {
    private final RegistrationActionPolicyRepository repository;
    private final RegistrationActionPolicyEvaluator evaluator = new RegistrationActionPolicyEvaluator();

    /** 인증 후 한 번 조회하여 같은 요청의 모든 검증에 전달한다. */
    public List<RegistrationActionPolicyInput> loadEnabledRegistrationActionPolicies(String eventId, Collection<RegistrationActionType> actionTypes) {
        // 엔티티 조회 결과를 순수 판정 모델로 변환하여 요청 내에서 재사용한다.
        return repository.findAllByEventIdAndActionTypeInAndEnabledTrueOrderByActionTypeAscRegistrationStartAtAscIdAsc(eventId, actionTypes)
                .stream().map(this::toRegistrationActionPolicyModel).toList();
    }

    /** 단일 작업 API의 정책을 해당 enum 조건으로만 조회한다. */
    public List<RegistrationActionPolicyInput> loadEnabledRegistrationActionPolicies(String eventId, RegistrationActionType actionType) {
        return repository.findAllByEventIdAndActionTypeAndEnabledTrueOrderByRegistrationStartAtAscIdAsc(eventId, actionType)
                .stream().map(this::toRegistrationActionPolicyModel).toList();
    }

    /** 전역 조건을 통과한 경우에만 신규 접수 정책을 조회하고 요청시각으로 평가한다. */
    public Decision evaluateNewRegistrationPolicy(Event event, LocalDateTime now) {
        // 전역 마감의 존재 여부가 아니라 현재 요청의 통과 여부로 분기한다.
        EventInput input = event == null ? null : toEventActionPolicyInput(event);
        Decision global = evaluator.evaluateRegistrationActionPolicy(input, null, Action.REGIST, now, List.of());
        if (!global.allowed()) { return global; }

        // 정책 행이 없으면 빈 목록으로 평가하고 조회 오류는 호출부로 전파한다.
        List<RegistrationActionPolicyInput> policies = loadEnabledRegistrationActionPolicies(event.getId(), RegistrationActionType.REGIST);
        return evaluator.evaluateRegistrationActionPolicy(input, null, Action.REGIST, now, policies);
    }

    /** 신규 생성 경로에서만 접수 정책을 실제 업무 오류에 연결한다. */
    public void validateNewRegistrationPolicy(Event event, LocalDateTime now) {
        requireRegistrationActionAllowed(evaluateNewRegistrationPolicy(event, now), Action.REGIST);
    }

    /** 복합 수정에서 이미 조회한 정책을 재사용하여 신규 인원 추가를 검증한다. */
    public void validateOrganizationMemberAdditionPolicy(Event event, LocalDateTime now, List<RegistrationActionPolicyInput> policies) {
        Decision decision = evaluator.evaluateRegistrationActionPolicy(
                toEventActionPolicyInput(event), null, Action.ADD_MEMBER, now, policies);
        requireRegistrationActionAllowed(decision, Action.ADD_MEMBER);
    }

    /** 영속성 구조와 서버 의존성이 없는 판정 입력을 분리한다. */
    private RegistrationActionPolicyInput toRegistrationActionPolicyModel(RegistrationActionPolicy policy) {
        // 영속 enum을 각 서버의 판정 작업으로 변환한다. 새 저장 작업 추가 시 switch 누락을 컴파일로 확인한다.
        Action action = switch (policy.getActionType()) {
            case REGIST -> Action.REGIST;
            case MODIFY -> Action.MODIFY;
            case REFUND -> Action.REFUND;
            case PAYMENT -> Action.PAYMENT;
        };
        return new RegistrationActionPolicyInput(policy.getId(), policy.getEventId(), action,
                policy.getRegistrationStartAt(), policy.getRegistrationEndAt(),
                policy.getEffectiveFrom(), policy.isEnabled());
    }

    /** 신청 엔티티에서 판정에 필요한 값만 추출한다. */
    public static ParticipantInput toParticipantActionPolicyInput(Registration registration) {
        boolean unpaid = registration.getPaidAmount().signum() == 0
                && (registration.getStatus() == RegistrationStatus.PAYMENT_PENDING
                    || registration.getStatus() == RegistrationStatus.EXPIRED);
        return new ParticipantInput(registration.getRegistrationDate(), unpaid, registration.isExternalPayment(), !registration.isSoftDeleted()
                && registration.getContractAmount().compareTo(registration.getPaidAmount()) > 0);
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
            LocalDateTime now, List<RegistrationActionPolicyInput> policies) {
        Decision decision = evaluator.evaluateRegistrationActionPolicy(toEventActionPolicyInput(event), toParticipantActionPolicyInput(registration), action, now, policies);
        requireRegistrationActionAllowed(decision, action);
    }

    /** 같은 작업의 대상들을 함께 판정하여 구성원 순서와 무관한 대표 사유로 차단한다. */
    public void validateRegistrationActionsPolicy(Event event, List<Registration> registrations, Action action,
            LocalDateTime now, List<RegistrationActionPolicyInput> policies, boolean organizationScope) {
        Decision decision = evaluator.evaluateRegistrationActionsPolicy(toEventActionPolicyInput(event),
                registrations.stream().map(RegistrationActionPolicyService::toParticipantActionPolicyInput).toList(),
                action, now, policies, organizationScope);
        requireRegistrationActionAllowed(decision, action);
    }

    /** 정책 결과를 명시적인 상세 응답 값으로 반환한다. */
    public UserPolicy evaluateRegistrationUserPolicy(Event event, Registration registration, LocalDateTime now, List<RegistrationActionPolicyInput> policies) {
        return evaluator.evaluateRegistrationUserPolicy(toEventActionPolicyInput(event), toParticipantActionPolicyInput(registration), now, policies);
    }

    /** 대표 사유를 업무 예외로 변환하고 신청 제한의 KST 기간을 공개 메시지에 포함한다. */
    public static void requireRegistrationActionAllowed(Decision decision, Action action) {
        if (decision.allowed()) { return; }
        Reason reason = decision.reason();
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
            case REGISTRATION_PERIOD_BLOCKED -> ErrorCode.REGISTRATION_PERIOD_BLOCKED;
        };
        // 신청 제한에만 기간 문구가 있으며 다른 사유는 기존 기본 문구를 유지한다.
        String responseMessage = code.getMessage() + decision.restrictionPeriodMessage();
        throw CustomException.withResponseMessage(code, responseMessage);
    }
}
