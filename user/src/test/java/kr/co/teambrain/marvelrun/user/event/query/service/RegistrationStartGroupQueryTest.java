package kr.co.teambrain.marvelrun.user.event.query.service;
import kr.co.teambrain.marvelrun.user.discount.query.DiscountQueryService;
import kr.co.teambrain.marvelrun.common.discount.model.DiscountModels;
import kr.co.teambrain.marvelrun.common.discount.model.RegistrationPriceCalculationSnapshot;
import kr.co.teambrain.marvelrun.user.discount.domain.RegistrationPriceCalculation;
import kr.co.teambrain.marvelrun.user.discount.repository.RegistrationPriceCalculationRepository;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import kr.co.teambrain.marvelrun.common.inheritance_enum.EventStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.user.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.EventCategoryStartGroup;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.repository.EventCategoryStartGroupRepository;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyEvaluator;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyModels;
import kr.co.teambrain.marvelrun.user.event.policy.RegistrationActionPolicyService;
import kr.co.teambrain.marvelrun.user.event.query.assembler.RegistrationOptionResponseAssembler;
import kr.co.teambrain.marvelrun.user.event.query.dto.OrgRegistrationParticipantResponse;
import kr.co.teambrain.marvelrun.user.event.query.dto.OrgRegistrationQueryResponse;
import kr.co.teambrain.marvelrun.user.event.query.dto.RegistrationQueryResponse;
import kr.co.teambrain.marvelrun.user.event.query.dto.response.RegistrationOptionResponse;
import kr.co.teambrain.marvelrun.user.event.query.repository.EventCategorySouvenirQueryRepository;
import kr.co.teambrain.marvelrun.user.event.query.repository.EventQueryRepository;
import kr.co.teambrain.marvelrun.user.event.query.repository.RegistrationQueryData;
import kr.co.teambrain.marvelrun.user.event.query.repository.RegistrationQueryRepository;
import kr.co.teambrain.marvelrun.user.event.query.repository.projection.RegistrationOptionProjection;
import kr.co.teambrain.marvelrun.user.event.query.support.RegistrationPaymentQueryResolver;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** 선택지와 개인·단체 조회의 그룹 계약을 한곳에서 검증한다. DB 연결은 사용하지 않는다. */
class RegistrationStartGroupQueryTest {
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 14, 12, 0);
    private final EventCategoryStartGroupRepository groups = mock(EventCategoryStartGroupRepository.class);
    private final RegistrationQueryRepository registrations = mock(RegistrationQueryRepository.class);
    private final RegistrationActionPolicyService policies = mock(RegistrationActionPolicyService.class);
    private final ServerTimeProvider time = mock(ServerTimeProvider.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final RegistrationPriceCalculationRepository calculations = mock(RegistrationPriceCalculationRepository.class);
    private final RegistrationActionPolicyModels.EventInput event = new RegistrationActionPolicyModels.EventInput(
            "event", EventStatus.OPEN, now.minusDays(10), now.plusDays(10), now.plusDays(10));

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
        RegistrationOptionResponse response = new EventQueryService(pricingQuery(), events, options,
                new RegistrationOptionResponseAssembler(), policies, time, groups).getRegistrationOptions("event");
        assertThat(response.registrationAllowed()).isFalse();
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

        assertThat(new EventQueryService(pricingQuery(), events, options, new RegistrationOptionResponseAssembler(), policies, time, groups)
                .getRegistrationOptions("event").categories()).isEmpty();
        verifyNoInteractions(groups);
    }

    /** 비활성 기존 선택도 표시하고 외부 결제 및 기존 정책 응답을 바꾸지 않는다. */
    @Test
    void personalLookupDisplaysInactiveStoredGroupAndPreservesPolicy() {
        RegistrationQueryData.Member member = member("r1", "g1", false, true);
        RegistrationAccessRequest access = new RegistrationAccessRequest("name", "1990-01-01", "010-0000-0000", "test");
        when(time.currentDateTime()).thenReturn(now);
        when(encoder.matches("test", "encoded")).thenReturn(true);
        when(registrations.personal("event", access)).thenReturn(List.of(member));
        when(groups.findByIdIn(List.of("g1"))).thenReturn(List.of(group("g1", "category", "B", false)));

        RegistrationQueryResponse response = service().findPersonalRegistrationDetails("event", access).getFirst();
        assertThat(response.startGroupId()).isEqualTo("g1");
        assertThat(response.startGroupCode()).isEqualTo("B");
        assertThat(response.startGroupUnselected()).isFalse();
        assertThat(response.externalPayment()).isTrue();
        assertThat(response.userPolicy()).isEqualTo(new RegistrationActionPolicyEvaluator().evaluateRegistrationUserPolicy(
                event, new RegistrationActionPolicyModels.ParticipantInput(member.registrationDate(), false, true, false), now, List.of()));
        JsonNode json = new ObjectMapper().valueToTree(response);
        assertThat(json.has("sortOrder")).isFalse();
        assertThat(json.has("minExpectedFinishSeconds")).isFalse();
        assertThat(json.get("hasPriceCalculation").asBoolean()).isFalse();
        assertThat(json.has("priceCalculation")).isFalse();
        verifyNoInteractions(calculations);
        verify(groups).findByIdIn(List.of("g1"));
        verifyNoMoreInteractions(groups);
    }

    /** 단체원 순서는 조회 결과를 그대로 사용하고 동일 그룹의 직접 선택과 미선택을 구분한다. */
    @Test
    void organizationLookupKeepsMemberOrderAndDeduplicatesGroupIds() {
        when(time.currentDateTime()).thenReturn(now);
        when(encoder.matches("test", "encoded")).thenReturn(true);
        when(registrations.organizations("event", "login")).thenReturn(List.of(new RegistrationQueryData.Organization(
                "org", "login", "encoded", "단체", "대표", "1990-01-01", "010-0000-0000", null,
                "주소", "상세", now.plusDays(10), event)));
        when(registrations.members("event", "org")).thenReturn(List.of(
                member("r2", "rear", true, false), member("r1", "rear", false, false)));
        when(groups.findByIdIn(List.of("rear"))).thenReturn(List.of(group("rear", "category", "B", true)));

        OrgRegistrationQueryResponse response = service().findOrganizationRegistrationDetails(
                "event", new OrganizationAccessRequest("login", "test")).getFirst();
        assertThat(response.loginId()).isEqualTo("login");
        assertThat(response.registrations()).extracting(OrgRegistrationParticipantResponse::registrationId)
                .containsExactly("r2", "r1");
        assertThat(response.registrations()).extracting(OrgRegistrationParticipantResponse::startGroupCode)
                .containsExactly("B", "B");
        assertThat(response.registrations()).extracting(OrgRegistrationParticipantResponse::startGroupUnselected)
                .containsExactly(true, false);
        verify(groups).findByIdIn(List.of("rear"));
        verifyNoMoreInteractions(groups);
    }

    /** 현재 참조의 저장 금액과 할인율을 반환하고 기존 계약금액·납부액 필드를 유지한다. */
    @Test
    void personalLookupReturnsStoredCalculationWithoutRepricing() {
        // 현재 참조에만 연결된 과거 할인 값을 준비한다.
        RegistrationAccessRequest access = preparePersonalCalculationLookup();
        DiscountModels.AppliedDiscount discount = new DiscountModels.AppliedDiscount(
                "policy", 2L, DiscountModels.Stage.EXTERNAL, DiscountModels.Method.PERCENT,
                new BigDecimal("50"), null, new BigDecimal("70000"), new BigDecimal("35000"), new BigDecimal("35000"));
        when(calculations.findAllById(List.of("calculation")))
                .thenReturn(List.of(calculation("r1", List.of(discount))));

        // 전체 취소 등으로 현재 금액이 달라도 마지막 산출 근거 자체는 변조하지 않는다.
        RegistrationQueryResponse response = service().findPersonalRegistrationDetails("event", access).getFirst();
        assertThat(response.hasPriceCalculation()).isTrue();
        assertThat(response.totalAmount()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(response.paidAmount()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(response.priceCalculation().finalAmount()).isEqualByComparingTo("35000");
        assertThat(response.priceCalculation().appliedDiscounts().getFirst().revision()).isEqualTo(2L);
        JsonNode json = new ObjectMapper().valueToTree(response);
        assertThat(json.at("/priceCalculation/appliedDiscounts/0/method").asText()).isEqualTo("PERCENT");
        verify(calculations).findAllById(List.of("calculation"));
        verifyNoMoreInteractions(calculations);
    }

    /** 단체별 금액을 합성하지 않고 기록 없는 기존 참가자와 할인 없는 신규 기록을 구분한다. */
    @Test
    void organizationLookupDistinguishesMissingRecordFromNoDiscount() {
        // 인증된 단체에 과거 참가자와 계산 기록을 가진 참가자를 함께 둔다.
        when(time.currentDateTime()).thenReturn(now);
        when(encoder.matches("test", "encoded")).thenReturn(true);
        when(registrations.organizations("event", "login")).thenReturn(List.of(new RegistrationQueryData.Organization(
                "org", "login", "encoded", "단체", "대표", "1990-01-01", "010-0000-0000", null,
                "주소", "상세", now.plusDays(10), event)));
        when(registrations.members("event", "org")).thenReturn(List.of(
                member("old", null, true, false), member("r1", null, true, false, "calculation")));
        when(calculations.findAllById(List.of("calculation"))).thenReturn(List.of(calculation("r1", List.of())));

        // 참가자별 유무와 빈 할인 목록을 직렬화한 실제 JSON에서 확인한다.
        OrgRegistrationQueryResponse response = service().findOrganizationRegistrationDetails(
                "event", new OrganizationAccessRequest("login", "test")).getFirst();
        JsonNode json = new ObjectMapper().valueToTree(response);
        assertThat(json.at("/registrations/0/hasPriceCalculation").asBoolean()).isFalse();
        assertThat(json.at("/registrations/0").has("priceCalculation")).isFalse();
        assertThat(json.at("/registrations/1/hasPriceCalculation").asBoolean()).isTrue();
        assertThat(response.registrations().get(1).priceCalculation().appliedDiscounts()).isEmpty();
        assertThat(response.registrations().get(1).priceCalculation().finalAmount()).isEqualByComparingTo("70000");
        verify(calculations).findAllById(List.of("calculation"));
        verifyNoMoreInteractions(calculations);
    }

    /** 기록 참조가 있는데 행이 없으면 정상적인 과거 신청으로 위장하지 않는다. */
    @Test
    void personalLookupRejectsMissingReferencedCalculation() {
        RegistrationAccessRequest access = preparePersonalCalculationLookup();
        assertThatThrownBy(() -> service().findPersonalRegistrationDetails("event", access))
                .isInstanceOfSatisfying(CustomException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.DISCOUNT_CONFIGURATION_ERROR));
    }

    /** 다른 신청의 계산 기록은 금액과 정책 식별자까지 외부에 노출하지 않는다. */
    @Test
    void personalLookupRejectsCalculationOwnedByAnotherRegistration() {
        RegistrationAccessRequest access = preparePersonalCalculationLookup();
        when(calculations.findAllById(List.of("calculation"))).thenReturn(List.of(calculation("another", List.of())));
        assertThatThrownBy(() -> service().findPersonalRegistrationDetails("event", access))
                .isInstanceOfSatisfying(CustomException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.DISCOUNT_CONFIGURATION_ERROR));
    }

    /** 지원하지 않는 기록 형식을 정상적인 가격 근거로 반환하지 않는다. */
    @Test
    void personalLookupRejectsUnsupportedCalculationSnapshotVersion() {
        RegistrationAccessRequest access = preparePersonalCalculationLookup();
        RegistrationPriceCalculation unsupported = RegistrationPriceCalculation.builder()
                .id("calculation").registrationId("r1")
                .snapshot(new RegistrationPriceCalculationSnapshot(2, List.of())).build();
        when(calculations.findAllById(List.of("calculation"))).thenReturn(List.of(unsupported));
        assertThatThrownBy(() -> service().findPersonalRegistrationDetails("event", access))
                .isInstanceOfSatisfying(CustomException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.DISCOUNT_CONFIGURATION_ERROR));
    }

    /** 본인 확인 실패 시 계산 기록 조회 자체를 하지 않는다. */
    @Test
    void personalLookupDoesNotReadCalculationBeforeAuthentication() {
        RegistrationAccessRequest access = preparePersonalCalculationLookup();
        when(encoder.matches("test", "encoded")).thenReturn(false);
        assertThatThrownBy(() -> service().findPersonalRegistrationDetails("event", access))
                .isInstanceOfSatisfying(CustomException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.REGISTRATION_ACCESS_DENIED));
        verifyNoInteractions(calculations);
    }

    /** 현재 계산 참조를 가진 개인 신청의 인증과 기본 조회를 구성한다. */
    private RegistrationAccessRequest preparePersonalCalculationLookup() {
        RegistrationAccessRequest access = new RegistrationAccessRequest("name", "1990-01-01", "010-0000-0000", "test");
        when(time.currentDateTime()).thenReturn(now);
        when(encoder.matches("test", "encoded")).thenReturn(true);
        when(registrations.personal("event", access)).thenReturn(List.of(member("r1", null, true, false, "calculation")));
        return access;
    }

    /** 정책 조회 없이 당시 값을 반환하는 계산 기록 fixture를 구성한다. */
    private RegistrationPriceCalculation calculation(String owner, List<DiscountModels.AppliedDiscount> discounts) {
        return RegistrationPriceCalculation.builder().id("calculation").registrationId(owner)
                .baseAmount(new BigDecimal("70000"))
                .finalAmount(new BigDecimal(discounts.isEmpty() ? "70000" : "35000"))
                .roundingAdjustmentAmount(BigDecimal.ZERO)
                .snapshot(new RegistrationPriceCalculationSnapshot(1, discounts)).build();
    }

    /** 실제 응답 조립 및 기존 결제 안내 계산을 사용하는 조회 서비스를 구성한다. */
    private RegistrationQueryService service() {
        return new RegistrationQueryService(encoder, registrations, new RegistrationPaymentQueryResolver(), time, policies, groups, calculations);
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

    /** 정상 납부된 신청의 현재 그룹 및 정책 입력을 구성한다. */
    private RegistrationQueryData.Member member(String id, String groupId, boolean unselected, boolean external) {
        return member(id, groupId, unselected, external, null);
    }

    /** 현재 참조 유무가 다른 신청을 만들어 과거 데이터와 신규 기록의 혼재를 검증한다. */
    private RegistrationQueryData.Member member(String id, String groupId, boolean unselected, boolean external, String calculationId) {
        return new RegistrationQueryData.Member(id, "name", null, "1990-01-01", "010-0000-0000", "encoded",
                GenderClass.M, "category", "5km", List.of(), "주소", "상세", null, false, null, null, null,
                RegistrationStatus.CONFIRMED, BigDecimal.TEN, BigDecimal.TEN, false, ReservationStatus.CONSUMED,
                now.plusDays(10), external, now.minusDays(2), event, groupId, unselected, calculationId);
    }
    /** 기존 옵션 계약 테스트는 가격 확장 책임을 별도 테스트에 위임한다. */
    private DiscountQueryService pricingQuery() {
        DiscountQueryService service = mock(DiscountQueryService.class);
        when(service.appendInternalPricingOptions(any(), any())).thenAnswer(call -> call.getArgument(1));
        return service;
    }}
