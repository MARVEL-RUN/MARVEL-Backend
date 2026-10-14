package kr.co.teambrain.marvelrun.admin.event.query;

import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationActionType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.teambrain.marvelrun.admin.user.query.dto.OrganizationDetailResponse;
import kr.co.teambrain.marvelrun.admin.user.query.dto.OrganizationMemberDto;
import kr.co.teambrain.marvelrun.admin.event.policy.RegistrationActionPolicyReasons.*;

import java.util.Optional;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategoryStartGroup;
import kr.co.teambrain.marvelrun.admin.event.command.repository.EventCategoryStartGroupRepository;
import kr.co.teambrain.marvelrun.admin.event.policy.RegistrationActionPolicyModels;
import kr.co.teambrain.marvelrun.admin.event.query.assembler.RegistrationOptionResponseAssembler;
import kr.co.teambrain.marvelrun.admin.event.query.dto.response.RegistrationOptionResponse;
import kr.co.teambrain.marvelrun.admin.event.query.repository.EventCategorySouvenirQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.projection.RegistrationOptionProjection;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.RegistrationActionPolicy;
import kr.co.teambrain.marvelrun.admin.event.command.application.valid.RegistrationPolicyValidator;
import kr.co.teambrain.marvelrun.admin.event.policy.RegistrationActionPolicyEvaluator;
import kr.co.teambrain.marvelrun.admin.event.policy.RegistrationActionPolicyModels.*;
import kr.co.teambrain.marvelrun.admin.event.query.repository.EventCategoryQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.EventQueryRepository;
import kr.co.teambrain.marvelrun.admin.event.query.repository.RegistrationActionPolicyRepository;
import kr.co.teambrain.marvelrun.admin.event.query.service.EventQueryService;
import kr.co.teambrain.marvelrun.admin.event.query.support.RegistrationActionPolicyReader;
import kr.co.teambrain.marvelrun.common.inheritance_enum.EventStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.*;

/** DB·PG 없이 대상 신청일과 요청시각의 독립 경계 및 작업별 제한을 검증한다. */
class RegistrationPolicyDetailTest {
    private final RegistrationActionPolicyReader policies = mock(RegistrationActionPolicyReader.class);
    private final ServerTimeProvider time = mock(ServerTimeProvider.class);
    private final EventCategoryStartGroupRepository groups = mock(EventCategoryStartGroupRepository.class);
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 14, 12, 0);

    /** 없는 대회는 기존 업무 오류를 반환하고 선택지·정책 조회를 진행하지 않는다. */
    @Test void registrationOptionsRejectMissingEvent() {
        EventQueryRepository events = mock(EventQueryRepository.class);
        EventCategorySouvenirQueryRepository options = mock(EventCategorySouvenirQueryRepository.class);
        EventQueryService service = new EventQueryService(events, mock(EventCategoryQueryRepository.class), options,
                new RegistrationOptionResponseAssembler(), policies, time, groups);

        assertThatThrownBy(() -> service.getRegistrationOptions("missing"))
                .isInstanceOfSatisfying(CustomException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.EVENT_NOT_FOUND));
        verifyNoInteractions(options, policies, groups);
    }

    /** 접수 마감은 구간 조회 없이 판정하고, 열린 대회는 REGIST 정책을 조회한다. */
    @Test void registrationOptionsPolicyUsesExistingEvaluator() {
        // 전역 마감과 구간 차단에 사용할 실제 정책 판정기를 구성한다.
        RegistrationActionPolicyRepository repository = mock(RegistrationActionPolicyRepository.class);
        RegistrationActionPolicyReader reader = new RegistrationActionPolicyReader(repository);
        Event stored = mock(Event.class);
        when(stored.getId()).thenReturn("e");
        when(stored.getEventStatus()).thenReturn(EventStatus.OPEN);
        when(stored.getRegistStartDate()).thenReturn(now.minusDays(10));
        when(stored.getRegistDeadline()).thenReturn(now);

        // 전역 마감은 선택지 제공 여부와 별개로 false 사유를 반환한다.
        assertThat(reader.evaluateNewRegistrationPolicy(stored, now).reason()).isEqualTo(Reason.REGISTRATION_CLOSED);
        verifyNoInteractions(repository);

        // 열린 대회에서는 현재 요청시각을 포함하는 REGIST 구간을 평가한다.
        when(stored.getRegistDeadline()).thenReturn(null);
        RegistrationActionPolicy policy = RegistrationActionPolicy.builder().eventId("e")
                .actionType(RegistrationActionType.REGIST).registrationStartAt(now.minusDays(1))
                .registrationEndAt(now.plusDays(1)).effectiveFrom(now.minusDays(2)).enabled(true).build();
        when(repository.findAllByEventIdAndActionTypeAndEnabledTrueOrderByRegistrationStartAtAscIdAsc("e", RegistrationActionType.REGIST))
                .thenReturn(List.of(policy));
        assertThat(reader.evaluateNewRegistrationPolicy(stored, now).reason()).isEqualTo(Reason.REGISTRATION_PERIOD_BLOCKED);
    }
    /** 중복 종목 행을 일괄 조회하고 저장 안내문·그룹 순서·기존 기념품을 보존한다. */
    @Test
    void optionsBatchGroupsWithoutDuplicatingSouvenirsOrCalculatingDescriptions() {
        // 기념품 JOIN으로 같은 종목이 반복되는 목록을 구성한다.
        EventQueryRepository events = mock(EventQueryRepository.class);
        EventCategorySouvenirQueryRepository options = mock(EventCategorySouvenirQueryRepository.class);
        Event storedEvent = mock(Event.class);
        when(events.findById("event")).thenReturn(Optional.of(storedEvent));
        when(time.currentDateTime()).thenReturn(now);
        when(policies.evaluateNewRegistrationPolicy(storedEvent, now))
                .thenReturn(new RegistrationActionPolicyModels.Decision(RegistrationActionPolicyModels.Reason.REGISTRATION_CLOSED));
        // 내부에서 mock을 설정하는 fixture는 바깥 stubbing을 시작하기 전에 준비한다.
        List<RegistrationOptionProjection> projections = List.of(
                option("c2", "s1"), option("c2", "s2"), option("c1", "s3"));
        when(options.findRegistrationOptionsProjectionByEventId("event")).thenReturn(projections);
        when(groups.findByEventCategoryIdInAndActiveTrueOrderByEventCategoryIdAscSortOrderAsc(List.of("c2", "c1")))
                .thenReturn(List.of(group("g1", "c1", "A", true), group("g2", "c2", "A", true),
                        group("g3", "c2", "B", true)));

        // 대회 접수 제한과 무관하게 선택지를 반환하며 내부 경계값을 노출하지 않는다.
        RegistrationOptionResponse response = new EventQueryService(events, mock(EventCategoryQueryRepository.class), options,
                new RegistrationOptionResponseAssembler(), policies, time, groups).getRegistrationOptions("event");
        assertThat(response.registrationAllowed()).isFalse();
        assertThat(response.registrationReason().code().name()).isEqualTo("REGISTRATION_CLOSED");
        assertThat(response.categories()).extracting(RegistrationOptionResponse.EventCategoryOption::categoryId)
                .containsExactly("c2", "c1");
        assertThat(response.categories().getFirst().souvenirs()).hasSize(2);
        assertThat(response.categories().getFirst().startGroups())
                .extracting(RegistrationOptionResponse.StartGroupOption::id).containsExactly("g2", "g3");
        JsonNode selected = new ObjectMapper().valueToTree(response.categories().getFirst().startGroups().getFirst());
        assertThat(selected.size()).isEqualTo(3);
        assertThat(selected.get("description").asText()).isEqualTo("운영자가 저장한 안내문");
        verify(groups).findByEventCategoryIdInAndActiveTrueOrderByEventCategoryIdAscSortOrderAsc(List.of("c2", "c1"));
        verifyNoMoreInteractions(groups);
    }

    /** 빈 종목 목록에서는 불필요한 IN 조회를 수행하지 않는다. */
    @Test
    void optionsSkipGroupReadWhenCategoriesAreEmpty() {
        EventQueryRepository events = mock(EventQueryRepository.class);
        EventCategorySouvenirQueryRepository options = mock(EventCategorySouvenirQueryRepository.class);
        Event storedEvent = mock(Event.class);
        when(events.findById("event")).thenReturn(Optional.of(storedEvent));
        when(policies.evaluateNewRegistrationPolicy(eq(storedEvent), any()))
                .thenReturn(new RegistrationActionPolicyModels.Decision(null));

        assertThat(new EventQueryService(events, mock(EventCategoryQueryRepository.class), options, new RegistrationOptionResponseAssembler(), policies, time, groups)
                .getRegistrationOptions("event").categories()).isEmpty();
        verifyNoInteractions(groups);
    }

    /** 종목별 기념품 projection의 필수 표시값을 제공한다. */
    private RegistrationOptionProjection option(String categoryId, String souvenirId) {
        RegistrationOptionProjection projection = mock(RegistrationOptionProjection.class);
        when(projection.getEventCategoryId()).thenReturn(categoryId);
        when(projection.getEventCategoryName()).thenReturn("5km | 일반");
        when(projection.getEventCategoryOrder()).thenReturn(1L);
        when(projection.getEventCategoryAmount()).thenReturn(BigDecimal.TEN);
        when(projection.getEventCategoryIsActive()).thenReturn(true);
        when(projection.getSouvenirId()).thenReturn(souvenirId);
        when(projection.getSouvenirOrder()).thenReturn(1L);
        return projection;
    }

    /** 환산값과 무관하게 저장된 안내문을 반환하는 그룹 자료다. */
    private EventCategoryStartGroup group(String id, String categoryId, String code, boolean active) {
        return EventCategoryStartGroup.builder().id(id).eventCategoryId(categoryId).code(code)
                .description("운영자가 저장한 안내문").sortOrder("A".equals(code) ? 10 : 20).active(active).build();
    }


    private final RegistrationActionPolicyEvaluator evaluator = new RegistrationActionPolicyEvaluator();
    private final LocalDateTime start = LocalDateTime.of(2026, 10, 1, 0, 0);
    private final LocalDateTime end = start.plusDays(12);
    private final LocalDateTime effective = start.plusDays(11).plusHours(17);
    private final EventInput event = new EventInput("e", EventStatus.OPEN, start.minusMonths(1), null, null);

    /** NULL 전역 마감은 관리자 기존 검증과 목록 표시에서도 오류를 만들지 않는다. */
    @Test void nullableDeadlineIsAcceptedAndDisplayedInAdminEventList() {
        Event stored = Mockito.mock(Event.class);
        Mockito.when(stored.getId()).thenReturn("e");
        Mockito.when(stored.getNameKr()).thenReturn("테스트 대회");
        Mockito.when(stored.getEventStatus()).thenReturn(EventStatus.OPEN);
        Mockito.when(stored.getRegistStartDate()).thenReturn(start);
        assertThatCode(() -> new RegistrationPolicyValidator()
                .validateNewRegistrationPeriod(stored, effective)).doesNotThrowAnyException();
        EventQueryRepository repository =
                Mockito.mock(EventQueryRepository.class);
        Mockito.when(repository.findAllByOrderByRegistStartDateDesc()).thenReturn(List.of(stored));
        EventQueryService service =
                new EventQueryService(repository,
                        Mockito.mock(EventCategoryQueryRepository.class), mock(EventCategorySouvenirQueryRepository.class),
                        new RegistrationOptionResponseAssembler(), policies, time, groups);
        assertThat(service.getEventList().getFirst().registrationPeriod()).isEqualTo("2026.10.01 ~ 마감 없음");
    }

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
        List<RegistrationActionPolicyInput> policies = List.of(createEnabledRegistrationActionPolicy(Action.MODIFY, start, end),
                createEnabledRegistrationActionPolicy(Action.MODIFY, end, end.plusDays(12)), createEnabledRegistrationActionPolicy(Action.REFUND, start, end));
        ParticipantInput participant = new ParticipantInput(end, false, false);
        assertThat(evaluator.evaluateRegistrationActionPolicy(event, participant, Action.MODIFY, end.plusMonths(1), policies).allowed()).isFalse();
        assertThat(evaluator.evaluateRegistrationActionPolicy(event, participant, Action.REFUND, end.plusMonths(1), policies).allowed()).isTrue();
        assertThat(evaluator.evaluateRegistrationActionPolicy(event, participant, Action.PAYMENT, end.plusMonths(1), policies).allowed()).isTrue();
    }

    /** 미결제 수정·취소 면제와 결제 제한을 구분하고 0원 확정도 보호한다. */
    @Test void unpaidAndConfirmedZeroFeeAreDifferent() {
        List<RegistrationActionPolicyInput> policies = List.of(createEnabledRegistrationActionPolicy(Action.MODIFY, start, end), createEnabledRegistrationActionPolicy(Action.REFUND, start, end), createEnabledRegistrationActionPolicy(Action.PAYMENT, start, end));
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
        List<RegistrationActionPolicyInput> policies = List.of(new RegistrationActionPolicyInput("x", "other", Action.MODIFY, start, end, effective, true),
                new RegistrationActionPolicyInput("y", "e", Action.MODIFY, start, end, effective, false));
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

    /** REFUND 제한과 환불 없는 구성원 삭제를 서로 다른 정책 결과로 제공한다. */
    @Test
    void unpaidMemberDeletionDoesNotExposeRefundOrPaymentFields() {
        ParticipantInput unpaid = new ParticipantInput(start, true, false, true);
        List<RegistrationActionPolicyInput> policies = List.of(createEnabledRegistrationActionPolicy(Action.REFUND, start, end));
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
        List<RegistrationActionPolicyInput> policies = List.of(createEnabledRegistrationActionPolicy(Action.PAYMENT, start, end));
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
        List<RegistrationActionPolicyInput> policies = List.of(createEnabledRegistrationActionPolicy(Action.MODIFY, start, end),
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
        List<RegistrationActionPolicyInput> policies = List.of(createEnabledRegistrationActionPolicy(Action.MODIFY, start, end),
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
        List<RegistrationActionPolicyInput> policies = List.of(createEnabledRegistrationActionPolicy(Action.REFUND, start, end));
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

    /** 테스트 정책의 적용 시작을 고정한다. */
    private RegistrationActionPolicyInput createEnabledRegistrationActionPolicy(Action action, LocalDateTime from, LocalDateTime to) {
        return new RegistrationActionPolicyInput(action.name() + from, "e", action, from, to, effective, true);
    }

    /** 관리자 reader는 같은 정책을 참가자들에게 재사용하고 사용자 관점으로 결과를 반환한다. */
    @Test void readerReusesPolicyListAndSeparatesMembers() {
        RegistrationActionPolicyRepository repository =
                Mockito.mock(RegistrationActionPolicyRepository.class);
        RegistrationActionPolicyReader reader =
                new RegistrationActionPolicyReader(repository);
        Event storedEvent = Mockito.mock(Event.class);
        Mockito.when(storedEvent.getId()).thenReturn("e");
        Mockito.when(storedEvent.getEventStatus()).thenReturn(EventStatus.OPEN);
        Mockito.when(storedEvent.getRegistStartDate()).thenReturn(start.minusMonths(1));
        Registration protectedMember =
                Registration.builder()
                    .event(storedEvent).registrationDate(start).status(RegistrationStatus.CONFIRMED)
                    .contractAmount(BigDecimal.ZERO).paidAmount(BigDecimal.ZERO).build();
        Registration laterMember =
                Registration.builder()
                    .event(storedEvent).registrationDate(end).status(RegistrationStatus.CONFIRMED)
                    .contractAmount(BigDecimal.ZERO).paidAmount(BigDecimal.ZERO).build();
        Mockito.when(repository.findAllByEventIdAndActionTypeInAndEnabledTrueOrderByActionTypeAscRegistrationStartAtAscIdAsc("e", List.of(RegistrationActionType.MODIFY, RegistrationActionType.REFUND, RegistrationActionType.PAYMENT, RegistrationActionType.REGIST))).thenReturn(List.of(RegistrationActionPolicy.builder().id("b36f269a-7aee-4c61-9a05-8ce153f09f1d").eventId("e").actionType(RegistrationActionType.MODIFY).registrationStartAt(start).registrationEndAt(end).effectiveFrom(effective).enabled(true).build()));
        List<RegistrationActionPolicyInput> policies = reader.loadEnabledRegistrationActionPolicies("e", List.of(RegistrationActionType.MODIFY, RegistrationActionType.REFUND, RegistrationActionType.PAYMENT, RegistrationActionType.REGIST));
        OrganizationMemberPolicy protectedPolicy = reader.evaluateOrganizationMemberPolicy(protectedMember, effective, policies);
        OrganizationMemberPolicy laterPolicy = reader.evaluateOrganizationMemberPolicy(laterMember, effective, policies);
        OrganizationPolicy organizationPolicy = reader.evaluateOrganizationUserPolicy(
                storedEvent, List.of(protectedMember, laterMember), effective, policies);
        assertThat(protectedPolicy.modifyAllowed()).isFalse();
        assertThat(laterPolicy.modifyAllowed()).isTrue();
        assertThat(organizationPolicy.addMemberAllowed()).isTrue();

        // 관리자 실제 DTO도 사용자와 동일한 명시적 키 및 메시지를 직렬화한다.
        OrganizationDetailResponse response = OrganizationDetailResponse.builder()
                .organizationPolicy(organizationPolicy)
                .members(List.of(OrganizationMemberDto.builder().memberPolicy(protectedPolicy).build())).build();
        JsonNode json = new ObjectMapper().valueToTree(response);
        assertThat(json.has("userPolicy")).isFalse();
        assertThat(json.get("organizationPolicy").has("paymentAllowed")).isTrue();
        JsonNode memberJson = json.get("members").get(0);
        assertThat(memberJson.has("userPolicy")).isFalse();
        assertThat(memberJson.get("memberPolicy").get("modifyReason").get("message").asText())
                .contains("배송을 위한 신청 내역 확정");
        Mockito.verify(repository, Mockito.times(1)).findAllByEventIdAndActionTypeInAndEnabledTrueOrderByActionTypeAscRegistrationStartAtAscIdAsc("e", List.of(RegistrationActionType.MODIFY, RegistrationActionType.REFUND, RegistrationActionType.PAYMENT, RegistrationActionType.REGIST));
    }

    /** 관리자 단체 상세도 사용자 신규 인원 추가 제한과 KST 기간을 표시한다. */
    @Test
    void organizationAdditionShowsRegistrationRestrictionWithoutBlockingExistingActions() {
        RegistrationActionPolicyInput regist = new RegistrationActionPolicyInput("regist", "e", Action.REGIST, start, end, effective, true);
        OrganizationPolicy policy = evaluator.evaluateOrganizationUserPolicy(event,
                List.of(new ParticipantInput(start, false, false)), effective, List.of(regist));
        assertThat(policy.addMemberAllowed()).isFalse();
        assertThat(policy.addMemberReason().code()).isEqualTo(OrganizationAddMemberRestrictionReason.REGISTRATION_PERIOD_BLOCKED);
        assertThat(policy.addMemberReason().message()).isEqualTo(
                "현재는 신청이 일시 중단된 기간이므로 구성원을 추가할 수 없습니다. 신청 제한 기간(KST): 2026/10/12 17:00:00 ~ 2026/10/13 00:00:00 (종료시각 제외)");
        assertThat(policy.modifyAllowed()).isTrue();
        assertThat(policy.refundAllowed()).isTrue();
        assertThat(evaluator.evaluateOrganizationUserPolicy(event, List.of(), end, List.of(regist)).addMemberAllowed()).isTrue();

        // 전역 마감이 우선이면 구간 기간을 메시지에 덧붙이지 않는다.
        EventInput closed = new EventInput("e", EventStatus.OPEN, start.minusDays(1), effective, null);
        OrganizationPolicy closedPolicy = evaluator.evaluateOrganizationUserPolicy(closed, List.of(), effective, List.of(regist));
        assertThat(closedPolicy.addMemberReason().code()).isEqualTo(OrganizationAddMemberRestrictionReason.REGISTRATION_CLOSED);
        assertThat(closedPolicy.addMemberReason().message()).doesNotContain("KST");
    }

    /** 관리자 reader도 저장된 REGIST enum을 같은 내부 판정 작업으로 변환한다. */
    @Test
    void readerMapsRegistAlongsideDetailActions() {
        RegistrationActionPolicyRepository repository = Mockito.mock(RegistrationActionPolicyRepository.class);
        RegistrationActionPolicyReader reader = new RegistrationActionPolicyReader(repository);
        List<RegistrationActionType> types = List.of(RegistrationActionType.MODIFY, RegistrationActionType.REFUND,
                RegistrationActionType.PAYMENT, RegistrationActionType.REGIST);
        Mockito.when(repository.findAllByEventIdAndActionTypeInAndEnabledTrueOrderByActionTypeAscRegistrationStartAtAscIdAsc("e", types))
                .thenReturn(List.of(RegistrationActionPolicy.builder().eventId("e").actionType(RegistrationActionType.REGIST)
                        .registrationStartAt(start).registrationEndAt(end).effectiveFrom(effective).enabled(true).build()));
        assertThat(reader.loadEnabledRegistrationActionPolicies("e", types).getFirst().action()).isEqualTo(Action.REGIST);
        Mockito.verify(repository).findAllByEventIdAndActionTypeInAndEnabledTrueOrderByActionTypeAscRegistrationStartAtAscIdAsc("e", types);
        Mockito.verifyNoMoreInteractions(repository);
    }

    /** 저장소 장애를 정책 부재로 취급하지 않는다. */
    @Test void repositoryFailureIsNotAnEmptyPolicy() {
        RegistrationActionPolicyRepository repository =
                Mockito.mock(RegistrationActionPolicyRepository.class);
        Mockito.when(repository.findAllByEventIdAndActionTypeInAndEnabledTrueOrderByActionTypeAscRegistrationStartAtAscIdAsc("e", List.of(RegistrationActionType.MODIFY, RegistrationActionType.REFUND, RegistrationActionType.PAYMENT, RegistrationActionType.REGIST))).thenThrow(new DataAccessResourceFailureException("test"));
        RegistrationActionPolicyReader reader =
                new RegistrationActionPolicyReader(repository);
        assertThatThrownBy(() -> reader.loadEnabledRegistrationActionPolicies("e", List.of(RegistrationActionType.MODIFY, RegistrationActionType.REFUND, RegistrationActionType.PAYMENT, RegistrationActionType.REGIST))).isInstanceOf(DataAccessResourceFailureException.class);
    }
}
