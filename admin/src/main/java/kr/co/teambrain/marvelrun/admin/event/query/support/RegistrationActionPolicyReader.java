package kr.co.teambrain.marvelrun.admin.event.query.support;


import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationActionPolicyRepository;
import kr.co.teambrain.marvelrun.admin.event.policy.RegistrationActionPolicyEvaluator;
import kr.co.teambrain.marvelrun.admin.event.policy.RegistrationActionPolicyModels.*;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.RegistrationActionPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.List;

/** 관리자 상세에 사용자 관점의 정책 결과를 제공하며 관리자 명령을 차단하지 않는다. */
@Component
@RequiredArgsConstructor
public class RegistrationActionPolicyReader {
    private final RegistrationActionPolicyRepository repository;
    private final RegistrationActionPolicyEvaluator evaluator = new RegistrationActionPolicyEvaluator();

    /** 한 상세 요청에서 공유할 활성 정책 목록을 읽는다. */
    public List<Policy> loadEnabledRegistrationActionPolicies(String eventId) {
        // 엔티티 조회 결과를 순수 판정 모델로 변환하여 요청 내에서 재사용한다.
        return repository.findAllByEventIdAndEnabledTrueOrderByActionTypeAscRegistrationStartAtAscIdAsc(eventId)
                .stream().map(this::toRegistrationActionPolicyModel).toList();
    }

    /** 영속성 구조와 서버 의존성이 없는 판정 입력을 분리한다. */
    private Policy toRegistrationActionPolicyModel(RegistrationActionPolicy policy) {
        // 영속 enum을 각 서버의 판정 작업으로 변환한다. 새 저장 작업 추가 시 switch 누락을 컴파일로 확인한다.
        Action action = switch (policy.getActionType()) {
            case MODIFY -> Action.MODIFY;
            case REFUND -> Action.REFUND;
            case PAYMENT -> Action.PAYMENT;
        };
        return new Policy(policy.getId(), policy.getEventId(), action,
                policy.getRegistrationStartAt(), policy.getRegistrationEndAt(),
                policy.getEffectiveFrom(), policy.isEnabled());
    }

    /** 개인·단체 구성원별 사용자 허용 여부를 같은 정책으로 평가한다. */
    public UserPolicy evaluateRegistrationUserPolicy(Registration registration, LocalDateTime now, List<Policy> policies) {
        return evaluator.evaluateRegistrationUserPolicy(toEventActionPolicyInput(registration.getEvent()), toParticipantActionPolicyInput(registration), now, policies);
    }

    /** 구성원에는 수정과 환불을 포함한 삭제 정책만 제공한다. */
    public OrganizationMemberPolicy evaluateOrganizationMemberPolicy(Registration registration, LocalDateTime now, List<Policy> policies) {
        return evaluator.evaluateOrganizationMemberPolicy(toEventActionPolicyInput(registration.getEvent()),
                toParticipantActionPolicyInput(registration), now, policies);
    }

    /** 공통정보 변경과 신규 인원 추가를 분리한다. */
    public OrganizationPolicy evaluateOrganizationUserPolicy(Event event, List<Registration> members, LocalDateTime now, List<Policy> policies) {
        return evaluator.evaluateOrganizationUserPolicy(toEventActionPolicyInput(event), members.stream().map(this::toParticipantActionPolicyInput).toList(), now, policies);
    }

    /** 사용자 모듈과 동일한 값 모델에 대회 정보를 전달한다. */
    private EventInput toEventActionPolicyInput(Event event) {
        return new EventInput(event.getId(), event.getEventStatus(), event.getRegistStartDate(),
                event.getRegistDeadline(), event.getPaymentDeadline());
    }

    /** 0원 확정은 최초 미결제로 취급하지 않는다. */
    private ParticipantInput toParticipantActionPolicyInput(Registration registration) {
        boolean unpaid = registration.getPaidAmount().signum() == 0
                && (registration.getStatus() == RegistrationStatus.PAYMENT_PENDING || registration.getStatus() == RegistrationStatus.EXPIRED);
        return new ParticipantInput(registration.getRegistrationDate(), unpaid, registration.isExternalPayment(), !registration.isSoftDeleted()
                && registration.getContractAmount().compareTo(registration.getPaidAmount()) > 0);
    }
}
