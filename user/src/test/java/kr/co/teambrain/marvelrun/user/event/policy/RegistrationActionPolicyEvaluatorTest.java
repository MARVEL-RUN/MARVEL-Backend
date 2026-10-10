package kr.co.teambrain.marvelrun.user.event.policy;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationActionType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyReasons.*;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;

import java.time.LocalDateTime;
import java.util.List;
import kr.co.teambrain.marvelrun.common.inheritance_enum.EventStatus;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.RegistrationActionPolicy;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyModels.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
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
        assertThat(evaluator.evaluateRegistrationUserPolicy(event, unpaid, effective, policies).refundAllowed()).isFalse();
        assertThat(evaluator.evaluateRegistrationActionPolicy(event, unpaid, Action.PAYMENT, effective, policies).allowed()).isFalse();
        assertThat(evaluator.evaluateRegistrationUserPolicy(event, new ParticipantInput(start, false, false), effective, policies).modifyAllowed()).isFalse();
    }

    /** 복수 제한에서는 외부결제를 우선하고 미결제 취소에는 기간을 면제한다. */
    @Test void externalPaymentReasonTakesPriorityOverDeadlineAndPeriod() {
        EventInput closed = new EventInput("e", EventStatus.OPEN, start.minusDays(1), effective, effective);
        Decision result = evaluator.evaluateRegistrationActionPolicy(closed, new ParticipantInput(start, false, true),
                Action.MODIFY, effective, List.of(createEnabledRegistrationActionPolicy(Action.MODIFY, start, end)));
        assertThat(result.reason()).isEqualTo(Reason.EXTERNAL_PAYMENT);
        assertThat(evaluator.evaluateRegistrationActionPolicy(closed, new ParticipantInput(start, true, false),
                Action.DELETE_MEMBER, effective, List.of()).allowed()).isTrue();
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
                        .id("b36f269a-7aee-4c61-9a05-8ce153f09f1d").eventId("e").actionType(RegistrationActionType.MODIFY).registrationStartAt(start)
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

    /** REFUND 제한과 환불 없는 구성원 삭제를 서로 다른 정책 결과로 제공한다. */
    @Test
    void unpaidMemberDeletionDoesNotExposeRefundOrPaymentFields() {
        ParticipantInput unpaid = new ParticipantInput(start, true, false, true);
        List<Policy> policies = List.of(createEnabledRegistrationActionPolicy(Action.REFUND, start, end));
        UserPolicy personal = evaluator.evaluateRegistrationUserPolicy(event, unpaid, effective, policies);
        OrganizationMemberPolicy member = evaluator.evaluateOrganizationMemberPolicy(event, unpaid, effective, policies);

        assertThat(personal.refundAllowed()).isFalse();
        assertThat(personal.refundReason().code()).isEqualTo(RefundRestrictionReason.REGISTRATION_PERIOD_REFUND);
        assertThat(member.deleteMemberAllowed()).isTrue();
        assertThat(member.deleteMemberReason()).isNull();
        JsonNode json = new ObjectMapper().valueToTree(member);
        assertThat(json.has("paymentAllowed")).isFalse();
        assertThat(json.has("refundAllowed")).isFalse();
        assertThat(json.has("deleteMemberReason")).isTrue();
        assertThat(json.get("deleteMemberReason").isNull()).isTrue();
    }

    /** 완료 여부는 결제 정책 boolean을 바꾸지 않으며 프론트가 별도 상태로 버튼을 판단한다. */
    @Test
    void paidParticipantStillReceivesPaymentPolicy() {
        ParticipantInput paid = new ParticipantInput(start, false, false, false);
        UserPolicy allowed = evaluator.evaluateRegistrationUserPolicy(event, paid, effective, List.of());
        UserPolicy blocked = evaluator.evaluateRegistrationUserPolicy(event, paid, effective,
                List.of(createEnabledRegistrationActionPolicy(Action.PAYMENT, start, end)));

        assertThat(allowed.paymentAllowed()).isTrue();
        assertThat(allowed.paymentReason()).isNull();
        assertThat(blocked.paymentAllowed()).isFalse();
        assertThat(blocked.paymentReason().code()).isEqualTo(PaymentRestrictionReason.REGISTRATION_PERIOD_PAYMENT);
        assertThat(blocked.paymentReason().message())
                .isEqualTo("배송을 위한 신청 내역 확정에 따라 결제할 수 없습니다. 필요 시 관리자에게 문의해 주세요.");
    }

    /** 완납자의 결제 제한은 신규 인원에게 전파하지 않지만 추가금이 있으면 통합 결제를 막는다. */
    @Test
    void organizationPaymentUsesOnlyOutstandingParticipants() {
        List<Policy> policies = List.of(createEnabledRegistrationActionPolicy(Action.PAYMENT, start, end));
        ParticipantInput paid = new ParticipantInput(start, false, false, false);
        ParticipantInput laterUnpaid = new ParticipantInput(end, true, false, true);
        ParticipantInput additional = new ParticipantInput(start, false, false, true);

        OrganizationPolicy allowed = evaluator.evaluateOrganizationUserPolicy(event,
                List.of(paid, laterUnpaid), effective, policies);
        OrganizationPolicy blocked = evaluator.evaluateOrganizationUserPolicy(event,
                List.of(additional, laterUnpaid), effective, policies);
        assertThat(allowed.paymentAllowed()).isTrue();
        assertThat(allowed.paymentReason()).isNull();
        assertThat(blocked.paymentAllowed()).isFalse();
        assertThat(blocked.paymentReason().code()).isEqualTo(OrganizationPaymentRestrictionReason.REGISTRATION_PERIOD_PAYMENT);

        OrganizationPolicy fullyPaid = evaluator.evaluateOrganizationUserPolicy(event, List.of(paid), effective, policies);
        assertThat(fullyPaid.paymentAllowed()).isTrue();
        EventInput closed = new EventInput("e", EventStatus.OPEN, start.minusDays(1), null, effective);
        assertThat(evaluator.evaluateOrganizationUserPolicy(closed, List.of(paid), effective, policies)
                .paymentReason().code()).isEqualTo(OrganizationPaymentRestrictionReason.PAYMENT_CLOSED);
    }

    /** 구성원 순서와 정책 조회 순서가 단체의 대표 제한 사유를 바꾸지 않는다. */
    @Test
    void organizationReasonsAreIndependentOfMemberOrder() {
        ParticipantInput restricted = new ParticipantInput(start, false, false, true);
        ParticipantInput external = new ParticipantInput(end, false, true, true);
        List<Policy> policies = List.of(createEnabledRegistrationActionPolicy(Action.MODIFY, start, end),
                createEnabledRegistrationActionPolicy(Action.REFUND, start, end),
                createEnabledRegistrationActionPolicy(Action.PAYMENT, start, end));
        OrganizationPolicy forward = evaluator.evaluateOrganizationUserPolicy(event,
                List.of(restricted, external), effective, policies);
        OrganizationPolicy reverse = evaluator.evaluateOrganizationUserPolicy(event,
                List.of(external, restricted), effective, policies);
        assertThat(forward).isEqualTo(reverse);
        assertThat(forward.modifyReason().code()).isEqualTo(OrganizationModificationRestrictionReason.EXTERNAL_PAYMENT);
        assertThat(forward.paymentReason().code()).isEqualTo(OrganizationPaymentRestrictionReason.EXTERNAL_PAYMENT);
        assertThat(forward.refundReason().code()).isEqualTo(OrganizationRefundRestrictionReason.EXTERNAL_PAYMENT);
        assertThat(forward.addMemberAllowed()).isTrue();
    }

    /** 데이터 입력 오류는 외부결제보다 우선하며 정책 미등록 자체는 오류가 아니다. */
    @Test
    void invalidGlobalIntervalHasHighestPriority() {
        EventInput invalid = new EventInput("e", EventStatus.OPEN, end, start, null);
        ParticipantInput external = new ParticipantInput(start, false, true, false);
        UserPolicy result = evaluator.evaluateRegistrationUserPolicy(invalid, external, effective, List.of());
        assertThat(result.modifyReason().code()).isEqualTo(ModificationRestrictionReason.CONFIGURATION_INVALID);
        assertThat(result.refundReason().code()).isEqualTo(RefundRestrictionReason.CONFIGURATION_INVALID);
        assertThat(result.paymentReason().code()).isEqualTo(PaymentRestrictionReason.EXTERNAL_PAYMENT);
    }

    /** 전역 마감과 구간 제한이 함께 있으면 전역 마감을 안내한다. */
    @Test
    void globalDeadlinePrecedesPeriodForEachOperation() {
        EventInput closed = new EventInput("e", EventStatus.OPEN, start.minusDays(1), effective, effective);
        ParticipantInput paid = new ParticipantInput(start, false, false, true);
        List<Policy> policies = List.of(createEnabledRegistrationActionPolicy(Action.MODIFY, start, end),
                createEnabledRegistrationActionPolicy(Action.REFUND, start, end),
                createEnabledRegistrationActionPolicy(Action.PAYMENT, start, end));
        UserPolicy personal = evaluator.evaluateRegistrationUserPolicy(closed, paid, effective, policies);
        OrganizationMemberPolicy member = evaluator.evaluateOrganizationMemberPolicy(closed, paid, effective, policies);
        OrganizationPolicy organization = evaluator.evaluateOrganizationUserPolicy(closed, List.of(paid), effective, policies);
        assertThat(personal.modifyReason().code()).isEqualTo(ModificationRestrictionReason.REGISTRATION_CLOSED);
        assertThat(personal.refundReason().code()).isEqualTo(RefundRestrictionReason.REGISTRATION_CLOSED);
        assertThat(personal.paymentReason().code()).isEqualTo(PaymentRestrictionReason.PAYMENT_CLOSED);
        assertThat(member.deleteMemberReason().code()).isEqualTo(DeleteMemberRestrictionReason.REGISTRATION_CLOSED);
        assertThat(organization.addMemberReason().code()).isEqualTo(OrganizationAddMemberRestrictionReason.REGISTRATION_CLOSED);
    }

    /** 실제 결제한 구성원은 삭제 시 환불 정책을 적용하며 문구는 삭제 작업으로 제공한다. */
    @Test
    void paidMemberDeletionUsesRefundPolicyAndDeletionMessage() {
        List<Policy> policies = List.of(createEnabledRegistrationActionPolicy(Action.REFUND, start, end));
        OrganizationMemberPolicy member = evaluator.evaluateOrganizationMemberPolicy(event,
                new ParticipantInput(start, false, false), effective, policies);
        assertThat(member.deleteMemberAllowed()).isFalse();
        assertThat(member.deleteMemberReason().code()).isEqualTo(DeleteMemberRestrictionReason.REGISTRATION_PERIOD_REFUND);
        assertThat(member.deleteMemberReason().message())
                .isEqualTo("배송을 위한 신청 내역 확정에 따라 구성원을 삭제할 수 없습니다. 필요 시 관리자에게 문의해 주세요.");
        JsonNode json = new ObjectMapper().valueToTree(member);
        assertThat(json.has("deleteMemberReasons")).isFalse();
        assertThat(json.get("deleteMemberReason").get("code").asText()).isEqualTo("REGISTRATION_PERIOD_REFUND");
    }

    /** 상세 응답과 실제 정책 예외가 같은 대표 사유를 사용한다. */
    @Test
    void commandErrorUsesTheSameRepresentativeReasonAsOrganizationDetail() {
        List<ParticipantInput> members = List.of(new ParticipantInput(start, false, false, true),
                new ParticipantInput(end, false, true, true));
        List<Policy> policies = List.of(createEnabledRegistrationActionPolicy(Action.PAYMENT, start, end));
        OrganizationPolicy detail = evaluator.evaluateOrganizationUserPolicy(event, members, effective, policies);
        Decision command = evaluator.evaluateRegistrationActionsPolicy(event, members,
                Action.PAYMENT, effective, policies, true);

        assertThat(detail.paymentReason().code()).isEqualTo(OrganizationPaymentRestrictionReason.EXTERNAL_PAYMENT);
        assertThatThrownBy(() -> RegistrationActionPolicyService.requireRegistrationActionAllowed(command, Action.PAYMENT))
                .isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.EXTERNAL_PAYMENT_REGISTRATION_RESTRICTED));
    }

    /** 저장 enum의 모든 작업이 문자열 해석 없이 같은 내부 판정 작업으로 변환된다. */
    @ParameterizedTest
    @EnumSource(RegistrationActionType.class)
    void storedActionEnumMapsToPolicyAction(RegistrationActionType storedAction) {
        RegistrationActionPolicyRepository repository = Mockito.mock(RegistrationActionPolicyRepository.class);
        RegistrationActionPolicyService service = new RegistrationActionPolicyService(repository);
        RegistrationActionPolicy stored = RegistrationActionPolicy.builder().eventId("e")
                .actionType(storedAction).registrationStartAt(start).registrationEndAt(end)
                .effectiveFrom(effective).enabled(true).build();
        Mockito.when(repository.findAllByEventIdAndEnabledTrueOrderByActionTypeAscRegistrationStartAtAscIdAsc("e"))
                .thenReturn(List.of(stored));
        Action expected = switch (storedAction) {
            case MODIFY -> Action.MODIFY;
            case REFUND -> Action.REFUND;
            case PAYMENT -> Action.PAYMENT;
        };
        assertThat(stored.getActionType()).isEqualTo(storedAction);
        assertThat(service.loadEnabledRegistrationActionPolicies("e").getFirst().action()).isEqualTo(expected);
    }

    /** 테스트 정책의 적용 시작을 고정한다. */
    private Policy createEnabledRegistrationActionPolicy(Action action, LocalDateTime from, LocalDateTime to) {
        return new Policy(action.name() + from, "e", action, from, to, effective, true);
    }
}
