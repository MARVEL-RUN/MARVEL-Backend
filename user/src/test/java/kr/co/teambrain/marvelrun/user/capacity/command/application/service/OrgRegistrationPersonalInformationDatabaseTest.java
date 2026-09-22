package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.RegistrationUniqueConstraint;
import kr.co.teambrain.marvelrun.user.event.command.application.context.OrgRegistrationModificationAccessContext;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrgRegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.OrganizationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.inner.OrgRegistrationModificationParticipantRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.OrgRegistrationCreateResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.service.*;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.*;
import kr.co.teambrain.marvelrun.user.payment.command.application.creator.AdditionalPaymentTargetResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 실제 MySQL에서 단체 정정의 자원 보존·쿼리 생략·원자성·구성원 집합 동시 변경을 확인한다. */
@Import({RegistrationModificationAccessValidator.class, OrgRegistrationModificationAccessValidator.class,
        RegistrationModificationCandidateValidator.class, OrgRegistrationModificationCandidateValidator.class,
        RegistrationUniqueInfoValidator.class, RegistrationInformationPolicyValidator.class,
        RegistrationPersonalInformationValidator.class, OrgRegistrationPersonalInformationValidator.class,
        RegistrationModificationClassifier.class, RegistrationPersonalInformationService.class,
        OrgRegistrationPersonalInformationService.class, OrgRegistrationModificationGuard.class,
        RegistrationModificationPaymentGuard.class, RegistrationModificationPricingService.class,
        RegistrationPersonalModificationService.class, OrgRegistrationModificationService.class,
        RegistrationModificationSettlementService.class, RegistrationModificationTransactionService.class,
        ReservationCapacityDiffService.class, CapacityModificationService.class, ReservationRemovalService.class,
        AdditionalPaymentTargetResolver.class,
        org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration.class})
@TestPropertySource(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector="
        + "kr.co.teambrain.marvelrun.user.capacity.command.application.service.RegistrationPersonalInformationDatabaseTest$SqlCapture")
class OrgRegistrationPersonalInformationDatabaseTest extends CapacityMvpTestSupport {
    @Autowired
    private RegistrationModificationTransactionService commands;
    @MockitoSpyBean
    private RegistrationModificationClassifier classifier;
    @MockitoSpyBean
    private RegistrationUniqueInfoValidator uniqueInfo;
    @MockitoSpyBean
    private OrgRegistrationPersonalInformationService informationService;
    @MockitoSpyBean
    private OrgRegistrationModificationGuard guard;

    /** 테스트는 DDL을 실행하지 않으며 목차 2 고유 인덱스가 없으면 명시적으로 실패한다. */
    @BeforeEach
    void requireActiveUniqueConstraint() {
        assertThat(n("""
                select count(*) from information_schema.statistics
                where table_schema = database() and table_name = 'registration'
                  and index_name = 'uk_registration_active_unique_info' and non_unique = 0
                """))
                .as("docs/schema/registration-active-unique-info.sql의 중복 확인과 수동 적용이 선행되어야 합니다")
                .isEqualTo(5);
    }

    /** 결제 상태는 고정한 보존 검증이며 실제 결제와 동시에 실행하는 검증을 뜻하지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"READY", "CONFIRMING", "UNKNOWN", "COMPLETED"})
    void preservesGroupResourcesAndSkipsPolicyQueries(String paymentStatus) {
        OrgRegistrationCreateResponse created = group(categoryA, categoryA);
        if (paymentStatus.equals("COMPLETED")) {
            mockApprovalSuccess();
            payments.confirm(confirmRequest(created.paymentId()));
        } else {
            jdbc.update("update payment set process_status=? where id=?", paymentStatus, created.paymentId());
        }
        OrgRegistrationModificationRequest original = request(created.organizationId());
        OrgRegistrationModificationParticipantRequest first = original.registrations().getFirst();
        OrgRegistrationModificationParticipantRequest edited = new OrgRegistrationModificationParticipantRequest(
                first.registrationId(), first.eventCategoryId(), first.selectedSouvenirList(), "단체 정정 이름",
                "010-2222-3333", first.birth(), GenderClass.F);
        OrgRegistrationModificationRequest modified = replaceFirst(original, edited);
        if (paymentStatus.equals("COMPLETED")) {
            modified = new OrgRegistrationModificationRequest(original.guardianConsent(), original.access(), List.of(edited,
                    renamed(original.registrations().get(1), "두 번째 정정")));
        }
        // 기존 신청 정정이므로 비활성화된 종목·기념품의 현재 신규 정책을 다시 적용하지 않는다.
        jdbc.update("update event_category set is_active=false where id=?", categoryA);
        jdbc.update("update souvenir set is_active=false where id=?", souvenirId);
        Map<String, Map<String, Object>> before = members(created.organizationId());
        Map<String, List<Map<String, Object>>> resources = resources();
        RegistrationPersonalInformationDatabaseTest.SqlCapture.begin();
        RegistrationModificationSettlementResult result;
        List<String> sql;
        try {
            result = commands.modifyOrganization(eventId, created.organizationId(), modified);
        } finally {
            sql = RegistrationPersonalInformationDatabaseTest.SqlCapture.end();
        }
        assertThat(result.orders()).isEmpty();
        assertThat(result.members()).hasSize(2);
        Map<String, Map<String, Object>> after = members(created.organizationId());
        assertThat(after.get(first.registrationId()).get("name")).isEqualTo("단체 정정 이름");
        assertThat(after.get(first.registrationId()).get("ph_num")).isEqualTo("010-2222-3333");
        assertThat(after.get(first.registrationId()).get("gender")).isEqualTo("F");
        assertThat(withoutInformation(after.get(first.registrationId()))).isEqualTo(withoutInformation(before.get(first.registrationId())));
        String unchangedId = original.registrations().get(1).registrationId();
        if (paymentStatus.equals("COMPLETED")) {
            assertThat(after.get(unchangedId).get("name")).isEqualTo("두 번째 정정");
            assertThat(withoutInformation(after.get(unchangedId))).isEqualTo(withoutInformation(before.get(unchangedId)));
        } else {
            assertThat(after.get(unchangedId)).isEqualTo(before.get(unchangedId));
        }
        assertThat(resources()).isEqualTo(resources);
        assertOnlyInformationSql(sql);
    }

    /** 순서만 달라진 NONE 요청은 version·수정시각·주문을 포함하여 아무 행도 변경하지 않는다. */
    @Test
    void unchangedListHasNoWrites() {
        OrgRegistrationCreateResponse created = group(categoryA, categoryA);
        OrgRegistrationModificationRequest original = request(created.organizationId());
        OrgRegistrationModificationRequest reordered = new OrgRegistrationModificationRequest(original.guardianConsent(), original.access(),
                List.of(original.registrations().get(1), original.registrations().getFirst()));
        Map<String, Map<String, Object>> before = members(created.organizationId());
        Map<String, List<Map<String, Object>>> resources = resources();
        RegistrationPersonalInformationDatabaseTest.SqlCapture.begin();
        List<String> sql;
        try {
            assertThat(commands.modifyOrganization(eventId, created.organizationId(), reordered).orders()).isEmpty();
        } finally {
            sql = RegistrationPersonalInformationDatabaseTest.SqlCapture.end();
        }
        assertThat(members(created.organizationId())).isEqualTo(before);
        assertThat(resources()).isEqualTo(resources);
        assertOnlyInformationSql(sql);
        assertThat(sql).noneMatch(statement -> statement.stripLeading().matches("(?is)^(update|insert|delete)\\b.*"));
    }

    /** 모든 구성원 flush 뒤 실패해도 앞 구성원의 개인정보까지 외부 명령 트랜잭션과 함께 롤백한다. */
    @Test
    void failureAfterFlushRollsBackAllMembers() {
        OrgRegistrationCreateResponse created = group(categoryA, categoryA);
        OrgRegistrationModificationRequest original = request(created.organizationId());
        OrgRegistrationModificationRequest modified = new OrgRegistrationModificationRequest(original.guardianConsent(), original.access(),
                original.registrations().stream().map(value -> renamed(value, "정정-" + value.registrationId())).toList());
        Map<String, Map<String, Object>> before = members(created.organizationId());
        Map<String, List<Map<String, Object>>> resources = resources();
        OrgRegistrationPersonalInformationService target = AopTestUtils.getUltimateTargetObject(informationService);
        assertThat(mockingDetails(target).isSpy()).isTrue();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_ARGUMENT);
        }).when(target).modify(any(OrgRegistrationModificationAccessContext.class), any(RegistrationModificationClassifier.Change.class));
        expectError(ErrorCode.INVALID_REGISTRATION_MODIFICATION_ARGUMENT,
                () -> commands.modifyOrganization(eventId, created.organizationId(), modified));
        assertThat(members(created.organizationId())).isEqualTo(before);
        assertThat(resources()).isEqualTo(resources);
    }

    /** 기존 맞교환 미지원·최종목록 중복·외부 활성 중복 모두 변경 없이 거절한다. */
    @ParameterizedTest
    @ValueSource(strings = {"swap", "final", "external"})
    void duplicateIdentitiesAreRejected(String duplicate) {
        OrgRegistrationCreateResponse created = group(categoryA, categoryA);
        OrgRegistrationModificationRequest original = request(created.organizationId());
        OrgRegistrationModificationParticipantRequest a = original.registrations().getFirst();
        OrgRegistrationModificationParticipantRequest b = original.registrations().get(1);
        String firstName = duplicate.equals("swap") ? b.name() : "최종 중복";
        String secondName = duplicate.equals("swap") ? a.name() : "최종 중복";
        if (duplicate.equals("external")) {
            String externalId = personal(categoryA, "S", "1990-01-01").registrationId();
            firstName = "충돌 없는 정정";
            secondName = s("select name from registration where id=?", externalId);
        }
        OrgRegistrationModificationRequest modified = new OrgRegistrationModificationRequest(original.guardianConsent(), original.access(), List.of(
                renamed(a, firstName), renamed(b, secondName)));
        Map<String, Map<String, Object>> before = members(created.organizationId());
        Map<String, List<Map<String, Object>>> resources = resources();
        expectError(ErrorCode.REGISTRATION_ALREADY_EXISTS,
                () -> commands.modifyOrganization(eventId, created.organizationId(), modified));
        assertThat(members(created.organizationId())).isEqualTo(before);
        assertThat(resources()).isEqualTo(resources);
    }

    /** Java 문자열 비교를 통과한 DB collation 중복도 고유 제약에서 실패하고 전체 단체를 롤백한다. */
    @Test
    void databaseCollationDuplicateRollsBackWholeGroup() {
        OrgRegistrationCreateResponse created = group(categoryA, categoryA);
        OrgRegistrationModificationRequest original = request(created.organizationId());
        OrgRegistrationModificationRequest modified = new OrgRegistrationModificationRequest(original.guardianConsent(), original.access(), List.of(
                renamed(original.registrations().getFirst(), "Identity"), renamed(original.registrations().get(1), "identity")));
        Map<String, Map<String, Object>> before = members(created.organizationId());
        Map<String, List<Map<String, Object>>> resources = resources();
        Throwable error = attempt(created.organizationId(), modified);
        assertThat(error).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(RegistrationUniqueConstraint.matches(error)).isTrue();
        assertThat(members(created.organizationId())).isEqualTo(before);
        assertThat(resources()).isEqualTo(resources);
    }

    /** 서로 다른 단체가 사전 중복 조회를 함께 통과해도 같은 정보를 동시에 저장할 수 없다. */
    @Test
    void differentGroupsCannotClaimSameIdentityConcurrently() throws Exception {
        OrgRegistrationCreateResponse first = group(categoryA);
        OrgRegistrationCreateResponse second = group(categoryA);
        OrgRegistrationModificationRequest a = request(first.organizationId());
        OrgRegistrationModificationRequest b = request(second.organizationId());
        OrgRegistrationModificationRequest aRequest = replaceFirst(a, renamed(a.registrations().getFirst(), "경합 이름"));
        OrgRegistrationModificationRequest bRequest = replaceFirst(b, renamed(b.registrations().getFirst(), "경합 이름"));
        Map<String, Map<String, Object>> aBefore = members(first.organizationId());
        Map<String, Map<String, Object>> bBefore = members(second.organizationId());
        Map<String, List<Map<String, Object>>> resources = resources();
        CountDownLatch checked = new CountDownLatch(2);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            checked.countDown();
            assertThat(checked.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(uniqueInfo).validateOtherActive(eq(eventId), anyString(), eq("경합 이름"), anyString(), anyString());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> aResult = executor.submit(() -> attempt(first.organizationId(), aRequest));
            Future<Throwable> bResult = executor.submit(() -> attempt(second.organizationId(), bRequest));
            Throwable aError = aResult.get(20, TimeUnit.SECONDS);
            Throwable bError = bResult.get(20, TimeUnit.SECONDS);
            assertThat((aError == null) ^ (bError == null)).isTrue();
            assertThat(RegistrationUniqueConstraint.matches(aError == null ? bError : aError)).isTrue();
            assertThat(members(aError == null ? second.organizationId() : first.organizationId()))
                    .isEqualTo(aError == null ? bBefore : aBefore);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(n("select count(*) from registration where event_id=? and name=? and is_del=0", eventId, "경합 이름")).isEqualTo(1);
        assertThat(resources()).isEqualTo(resources);
    }

    /** 분류 후 다른 요청이 구성원·종목·생년월일을 바꾸거나 개인정보를 먼저 정정하면 오래된 요청을 거절한다. */
    @ParameterizedTest
    @ValueSource(strings = {"add", "remove", "category", "birth", "information", "none"})
    void staleRequestPreservesWinningModification(String change) throws Exception {
        OrgRegistrationCreateResponse created = group(categoryA, categoryA);
        String orgId = created.organizationId();
        OrgRegistrationModificationRequest original = request(orgId);
        OrgRegistrationModificationParticipantRequest first = original.registrations().getFirst();
        OrgRegistrationModificationRequest stale = change.equals("information")
                ? replaceFirst(original, selected(first, categoryB, "1990-01-01"))
                : change.equals("none") ? original : replaceFirst(original, renamed(first, "오래된 정정"));
        OrgRegistrationModificationRequest winner = switch (change) {
            case "add", "none" -> new OrgRegistrationModificationRequest(original.guardianConsent(), original.access(), List.of(first,
                    original.registrations().get(1), new OrgRegistrationModificationParticipantRequest(null, categoryA,
                    List.of(new SouvenirJson(souvenirId, "S")), "추가 구성원", "010-0000-0000", "1990-01-01", GenderClass.M)));
            case "remove" -> new OrgRegistrationModificationRequest(original.guardianConsent(), original.access(), List.of(first));
            case "category" -> replaceFirst(original, selected(first, categoryB, first.birth()));
            case "birth" -> replaceFirst(original, selected(first, categoryA, "1991-01-01"));
            case "information" -> replaceFirst(original, renamed(first, "먼저 정정"));
            default -> throw new AssertionError(change);
        };
        CountDownLatch classified = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (invocation.getArgument(1) == stale) {
                classified.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        }).when(classifier).classifyOrganization(anyList(), any());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> staleResult = executor.submit(() -> attempt(orgId, stale));
            assertThat(classified.await(10, TimeUnit.SECONDS)).isTrue();
            commands.modifyOrganization(eventId, orgId, winner);
            Map<String, Map<String, Object>> winnerMembers = members(orgId);
            Map<String, List<Map<String, Object>>> winnerResources = resources();
            release.countDown();
            assertConcurrent(staleResult.get(20, TimeUnit.SECONDS));
            assertThat(members(orgId)).isEqualTo(winnerMembers);
            assertThat(resources()).isEqualTo(winnerResources);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 개인정보 보호가 풀리기 전에 전체 요청이 동시 수정 오류로 종료되어 Event와 단체 사이 대기 순환을 만들지 않는다. */
    @Test
    void fullRequestOverlappingProtectedInformationCannotOverwriteIt() throws Exception {
        OrgRegistrationCreateResponse created = group(categoryA, categoryA);
        String orgId = created.organizationId();
        OrgRegistrationModificationRequest original = request(orgId);
        OrgRegistrationModificationRequest info = replaceFirst(original, renamed(original.registrations().getFirst(), "보호 중 정정"));
        OrgRegistrationModificationRequest full = replaceFirst(original, selected(original.registrations().getFirst(), categoryB, "1990-01-01"));
        CountDownLatch protectedInformation = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OrgRegistrationModificationGuard target = AopTestUtils.getUltimateTargetObject(guard);
        assertThat(mockingDetails(target).isSpy()).isTrue();
        doAnswer(invocation -> {
            OrgRegistrationModificationAccessContext access = invocation.getArgument(0);
            invocation.callRealMethod();
            if (access.request() == info) {
                protectedInformation.countDown();
                assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
            }
            return null;
        }).when(target).protect(any(OrgRegistrationModificationAccessContext.class));
        Map<String, List<Map<String, Object>>> beforeResources = resources();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> infoResult = executor.submit(() -> attempt(orgId, info));
            assertThat(protectedInformation.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> fullResult = executor.submit(() -> attempt(orgId, full));
            // 개인정보 트랜잭션을 계속 보유한 상태에서 즉시 거절되어야 한다. 데드락도 통과로 인정하지 않는다.
            assertConcurrent(fullResult.get(5, TimeUnit.SECONDS));
            release.countDown();
            assertThat(infoResult.get(20, TimeUnit.SECONDS)).isNull();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(s("select name from registration where id=?", original.registrations().getFirst().registrationId())).isEqualTo("보호 중 정정");
        assertThat(s("select event_category_id from registration where id=?", original.registrations().getFirst().registrationId())).isEqualTo(categoryA);
        assertThat(resources()).isEqualTo(beforeResources);
    }

    /** DB fixture에서 현재 활성 구성원 전체와 합성 인증정보를 읽어 기존 요청 형식을 만든다. */
    private OrgRegistrationModificationRequest request(String orgId) {
        List<OrgRegistrationModificationParticipantRequest> participants = jdbc.query("""
                select id,event_category_id,name,ph_num,birth,gender from registration
                where organization_id=? and is_del=0 order by id
                """, (rs, row) -> new OrgRegistrationModificationParticipantRequest(rs.getString("id"),
                rs.getString("event_category_id"), List.of(new SouvenirJson(souvenirId, "S")), rs.getString("name"),
                rs.getString("ph_num"), rs.getString("birth"), GenderClass.valueOf(rs.getString("gender"))), orgId);
        return new OrgRegistrationModificationRequest(true, new OrganizationAccessRequest(
                s("select login_id from organization where id=?", orgId), "Test1234!"), participants);
    }

    /** 나머지 구성원은 유지하고 첫 참가자 요청만 교체한다. */
    private OrgRegistrationModificationRequest replaceFirst(OrgRegistrationModificationRequest request,
                                                            OrgRegistrationModificationParticipantRequest first) {
        List<OrgRegistrationModificationParticipantRequest> participants = new ArrayList<>(request.registrations());
        participants.set(0, first);
        return new OrgRegistrationModificationRequest(request.guardianConsent(), request.access(), participants);
    }

    /** 개인정보 변경용으로 이름만 교체한다. */
    private OrgRegistrationModificationParticipantRequest renamed(OrgRegistrationModificationParticipantRequest request, String name) {
        return new OrgRegistrationModificationParticipantRequest(request.registrationId(), request.eventCategoryId(),
                request.selectedSouvenirList(), name, request.phNum(), request.birth(), request.gender());
    }

    /** 전체 수정용 종목 또는 생년월일 변경을 구성한다. */
    private OrgRegistrationModificationParticipantRequest selected(OrgRegistrationModificationParticipantRequest request, String category, String birth) {
        return new OrgRegistrationModificationParticipantRequest(request.registrationId(), category,
                List.of(new SouvenirJson(souvenirId, category.equals(categoryB) ? "M" : "S")),
                request.name(), request.phNum(), birth, request.gender());
    }

    /** 실패를 수집하되 예상하지 못한 오류를 성공으로 처리하지 않는다. */
    private Throwable attempt(String orgId, OrgRegistrationModificationRequest request) {
        try {
            commands.modifyOrganization(eventId, orgId, request);
            return null;
        } catch (RuntimeException error) {
            return error;
        }
    }

    /** 잠금 대기 시간 초과가 아닌 명시적인 오래된 요청 차단인지 확인한다. */
    private void assertConcurrent(Throwable error) {
        assertThat(error).isInstanceOfSatisfying(CustomException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION));
    }

    /** 제거된 구성원까지 포함하여 전체 저장값과 JSON을 비교한다. */
    private Map<String, Map<String, Object>> members(String orgId) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("select * from registration where organization_id=? order by id", orgId)) {
            String id = (String) row.get("id");
            row.put("souvenir_json", s("select cast(souvenir_json as char) from registration where id=?", id));
            result.put(id, row);
        }
        return result;
    }

    /** 단체 개인정보와 수정 메타데이터만 보존 비교에서 제외한다. */
    private Map<String, Object> withoutInformation(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        for (String key : List.of("name", "ph_num", "gender", "modified_at", "version")) { result.remove(key); }
        return result;
    }

    /** 단체 소유 주문도 포함하여 금융·예약·정원의 실제 저장값을 비교한다. */
    private Map<String, List<Map<String, Object>>> resources() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        result.put("organization", jdbc.queryForList("select * from organization where event_id=? order by id", eventId));
        result.put("payment", jdbc.queryForList("""
                select p.* from payment p left join organization o on o.id=p.organization_id
                left join registration r on r.id=p.registration_id where o.event_id=? or r.event_id=? order by p.id
                """, eventId, eventId));
        result.put("allocation", jdbc.queryForList("select a.* from payment_allocation a join registration r on r.id=a.registration_id where r.event_id=? order by a.id", eventId));
        result.put("reservation", jdbc.queryForList("""
                select v.id,v.registration_id,v.status,v.version,v.hold_sequence,v.expires_at,v.created_at,v.updated_at,
                       cast(v.history as char) as history
                from reservation v join registration r on r.id=v.registration_id where r.event_id=? order by v.id
                """, eventId));
        result.put("items", jdbc.queryForList("""
                select i.* from reservation_item i join reservation v on v.id=i.reservation_id
                join registration r on r.id=v.registration_id where r.event_id=? order by i.id
                """, eventId));
        result.put("capacity", jdbc.queryForList("select * from capacity where event_id=? order by id", eventId));
        return result;
    }

    /** 허용한 단체·신청 잠금 외에는 자원·정책 조회와 이벤트 잠금이 없음을 실제 SQL로 확인한다. */
    private void assertOnlyInformationSql(List<String> statements) {
        assertThat(statements).isNotEmpty();
        boolean organizationLocked = false;
        boolean membersLocked = false;
        for (String statement : statements) {
            String sql = statement.toLowerCase(Locale.ROOT);
            assertThat(sql).doesNotMatch("(?s).*\\b(payment|payment_allocation|reservation|reservation_item|capacity|capacity_category|event_registration_policy|event_category_registration_policy|event_category_souvenir|event_category_souvenir_policy|souvenir)\\b.*");
            if (sql.contains("for update") || sql.contains("for share")) {
                assertThat(sql).doesNotMatch("(?s).*\\b(event|event_category)\\b.*");
                assertThat(sql).containsPattern("\\b(organization|registration)\\b");
                organizationLocked |= sql.contains("organization ");
                membersLocked |= sql.contains("registration ");
            }
        }
        assertThat(organizationLocked).isTrue();
        assertThat(membersLocked).isTrue();
    }
}
