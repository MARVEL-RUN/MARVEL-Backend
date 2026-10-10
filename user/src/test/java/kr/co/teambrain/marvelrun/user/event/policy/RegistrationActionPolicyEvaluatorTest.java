package kr.co.teambrain.marvelrun.user.event.policy;

import java.time.LocalDateTime;
import java.util.List;
import kr.co.teambrain.marvelrun.common.inheritance_enum.EventStatus;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationActionPolicy;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyModels.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.*;

/** DB·PG 없이 대상 신청일과 요청시각의 독립 경계 및 작업별 제한을 검증한다. */
class RegistrationActionPolicyEvaluatorTest {
    private final RegistrationActionPolicyEvaluator evaluator = new RegistrationActionPolicyEvaluator();
    private final LocalDateTime start = LocalDateTime.of(2026, 10, 1, 0, 0);
    private final LocalDateTime end = start.plusDays(12);
    private final LocalDateTime effective = start.plusDays(11).plusHours(17);
    private final EventInput event = new EventInput("e", EventStatus.OPEN, start.minusMonths(1), null, null);

    /** 신청일 종료는 제외하고 적용 시작 정각부터 제한한다. */
    @ParameterizedTest
    @CsvSource({"-1,0,true", "0,-1,true", "0,0,false", "1036799,0,false", "1036800,0,true"})
    void registrationAndEffectiveBoundaries(long registrationSeconds, long effectiveSeconds, boolean allowed) {
        Decision result = evaluator.evaluateRegistrationActionPolicy(event,
                new ParticipantInput(start.plusSeconds(registrationSeconds), false, false), Action.MODIFY,
                effective.plusSeconds(effectiveSeconds), List.of(createEnabledRegistrationActionPolicy(Action.MODIFY, start, end)));
        assertThat(result.allowed()).isEqualTo(allowed);
    }

    /** 여러 차수의 적용 시작이 겹쳐도 신청일 구간과 작업이 일치하는 정책만 적용한다. */
    @Test void separateStagesAndActions() {
        List<Policy> policies = List.of(createEnabledRegistrationActionPolicy(Action.MODIFY, start, end),
                createEnabledRegistrationActionPolicy(Action.MODIFY, end, end.plusDays(12)), createEnabledRegistrationActionPolicy(Action.REFUND, start, end));
        ParticipantInput participant = new ParticipantInput(end, false, false);
        assertThat(evaluator.evaluateRegistrationActionPolicy(event, participant, Action.MODIFY, end.plusMonths(1), policies).allowed()).isFalse();
        assertThat(evaluator.evaluateRegistrationActionPolicy(event, participant, Action.REFUND, end.plusMonths(1), policies).allowed()).isTrue();
        assertThat(evaluator.evaluateRegistrationActionPolicy(event, participant, Action.PAYMENT, end.plusMonths(1), policies).allowed()).isTrue();
    }

    /** 미결제 수정·취소 면제와 결제 제한을 구분하고 0원 확정도 보호한다. */
    @Test void unpaidAndConfirmedZeroFeeAreDifferent() {
        List<Policy> policies = List.of(createEnabledRegistrationActionPolicy(Action.MODIFY, start, end), createEnabledRegistrationActionPolicy(Action.REFUND, start, end), createEnabledRegistrationActionPolicy(Action.PAYMENT, start, end));
        ParticipantInput unpaid = new ParticipantInput(start, true, false);
        assertThat(evaluator.evaluateRegistrationUserPolicy(event, unpaid, effective, policies).modifyAllowed()).isTrue();
        assertThat(evaluator.evaluateRegistrationUserPolicy(event, unpaid, effective, policies).refundAllowed()).isTrue();
        assertThat(evaluator.evaluateRegistrationActionPolicy(event, unpaid, Action.PAYMENT, effective, policies).allowed()).isFalse();
        assertThat(evaluator.evaluateRegistrationUserPolicy(event, new ParticipantInput(start, false, false), effective, policies).modifyAllowed()).isFalse();
    }

    /** 전역 마감과 외부결제·구간 제한은 한 결과에 보존한다. */
    @Test void globalAndPeriodReasonsAccumulate() {
        EventInput closed = new EventInput("e", EventStatus.OPEN, start.minusDays(1), effective, effective);
        Decision result = evaluator.evaluateRegistrationActionPolicy(closed, new ParticipantInput(start, false, true),
                Action.MODIFY, effective, List.of(createEnabledRegistrationActionPolicy(Action.MODIFY, start, end)));
        assertThat(result.reasons()).containsExactly(Reason.REGISTRATION_CLOSED, Reason.EXTERNAL_PAYMENT, Reason.REGISTRATION_PERIOD_MODIFY);
        assertThat(evaluator.evaluateRegistrationActionPolicy(closed, new ParticipantInput(start, true, false),
                Action.REFUND, effective, List.of()).allowed()).isTrue();
        assertThat(evaluator.evaluateRegistrationActionPolicy(closed, new ParticipantInput(start, true, false),
                Action.MODIFY, effective, List.of()).allowed()).isFalse();
    }

    /** 비활성·다른 대회의 정책과 정책 부재는 차단 사유를 만들지 않는다. */
    @Test void inactiveAndOtherEventPoliciesDoNotBlock() {
        List<Policy> policies = List.of(new Policy("x", "other", Action.MODIFY, start, end, effective, true),
                new Policy("y", "e", Action.MODIFY, start, end, effective, false));
        assertThat(evaluator.evaluateRegistrationActionPolicy(event, new ParticipantInput(start, false, false),
                Action.MODIFY, effective, policies).allowed()).isTrue();
        assertThat(evaluator.evaluateRegistrationActionPolicy(event, null, Action.PAYMENT, effective, List.of()).allowed()).isTrue();
    }

    /** 전체 인원의 공통정보 수정 제한과 신규 참가자 추가 가능 여부는 독립적이다. */
    @Test void commonModificationDoesNotBlockNewMemberByPeriodPolicy() {
        OrganizationPolicy result = evaluator.evaluateOrganizationUserPolicy(event,
                List.of(new ParticipantInput(start, false, false), new ParticipantInput(end, true, false)),
                effective, List.of(createEnabledRegistrationActionPolicy(Action.MODIFY, start, end)));
        assertThat(result.modifyAllowed()).isFalse();
        assertThat(result.addMemberAllowed()).isTrue();
    }

    /** JPA 엔티티의 모든 정책 값이 순수 판정 모델로 전달되고 저장소 오류는 전파된다. */
    @Test
    void policyServiceMapsJpaEntityAndPropagatesRepositoryFailure() {
        RegistrationActionPolicyRepository repository = Mockito.mock(RegistrationActionPolicyRepository.class);
        RegistrationActionPolicyService service = new RegistrationActionPolicyService(repository);
        RegistrationActionPolicy stored =
                RegistrationActionPolicy.builder()
                        .id("b36f269a-7aee-4c61-9a05-8ce153f09f1d").eventId("e").actionType("MODIFY").registrationStartAt(start)
                        .registrationEndAt(end).effectiveFrom(effective).enabled(true).build();

        // 저장된 시각을 변환 없이 보존하며 작업 종류만 판정 enum으로 변환한다.
        Mockito.when(repository.findAllByEventIdAndEnabledTrueOrderByActionTypeAscRegistrationStartAtAscIdAsc("e"))
                .thenReturn(List.of(stored));
        assertThat(service.loadEnabledRegistrationActionPolicies("e"))
                .containsExactly(new Policy("b36f269a-7aee-4c61-9a05-8ce153f09f1d", "e", Action.MODIFY, start, end, effective, true));

        // 저장소 오류가 빈 정책으로 처리되어 제한을 우회하지 않는지 확인한다.
        Mockito.when(repository.findAllByEventIdAndEnabledTrueOrderByActionTypeAscRegistrationStartAtAscIdAsc("e"))
                .thenThrow(new DataAccessResourceFailureException("test"));
        assertThatThrownBy(() -> service.loadEnabledRegistrationActionPolicies("e"))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    /** 테스트 정책의 적용 시작을 고정한다. */
    private Policy createEnabledRegistrationActionPolicy(Action action, LocalDateTime from, LocalDateTime to) {
        return new Policy(action.name() + from, "e", action, from, to, effective, true);
    }
}
