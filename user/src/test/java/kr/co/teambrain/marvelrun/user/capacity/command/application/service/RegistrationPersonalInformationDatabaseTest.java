package kr.co.teambrain.marvelrun.user.capacity.command.application.service;

import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.CustomException;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorCode;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.RegistrationUniqueConstraint;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.GlobalExceptionHandler;
import kr.co.teambrain.marvelrun.user.common.exception.in_service.ErrorResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.context.RegistrationModificationAccessContext;
import kr.co.teambrain.marvelrun.user.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.RegistrationModificationSettlementResult;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationAccessRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.request.RegistrationModificationRequest;
import kr.co.teambrain.marvelrun.user.event.command.application.dto.response.RegistrationCreateResponse;
import kr.co.teambrain.marvelrun.user.event.command.application.service.*;
import kr.co.teambrain.marvelrun.user.event.command.application.valid.*;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 실제 MySQL에서 개인정보 정정의 조회 생략·보존·롤백과 두 종류의 동시 변경을 검증한다. */
@Import({RegistrationModificationAccessValidator.class, OrgRegistrationModificationAccessValidator.class,
        RegistrationModificationCandidateValidator.class, OrgRegistrationModificationCandidateValidator.class,
        RegistrationUniqueInfoValidator.class, RegistrationPersonalInformationValidator.class,
        RegistrationModificationClassifier.class, RegistrationPersonalInformationService.class,
        RegistrationModificationPaymentGuard.class, RegistrationModificationPricingService.class,
        RegistrationPersonalModificationService.class, OrgRegistrationModificationService.class,
        RegistrationModificationSettlementService.class, RegistrationModificationCommandService.class,
        ReservationCapacityDiffService.class, CapacityModificationService.class, ReservationRemovalService.class,
        org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration.class})
@TestPropertySource(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector="
        + "kr.co.teambrain.marvelrun.user.capacity.command.application.service.RegistrationPersonalInformationDatabaseTest$SqlCapture")
class RegistrationPersonalInformationDatabaseTest extends CapacityMvpTestSupport {
    @Autowired
    private RegistrationModificationCommandService commands;
    @MockitoSpyBean
    private RegistrationModificationClassifier classifier;
    @MockitoSpyBean
    private RegistrationUniqueInfoValidator uniqueInfo;
    @MockitoSpyBean
    private RegistrationPersonalInformationService informationService;

    /** 스키마 변경은 테스트가 수행하지 않는다. 미적용이면 안전성 검증을 건너뛰지 않고 실패시킨다. */
    @BeforeEach
    void requireActiveUniqueConstraint() {
        assertThat(n("""
                select count(*) from information_schema.statistics
                where table_schema = database() and table_name = 'registration'
                  and index_name = 'uk_registration_active_unique_info' and non_unique = 0
                """))
                .as("docs/schema/registration-active-unique-info.sql의 검토·수동 적용이 선행되어야 합니다")
                .isEqualTo(5);
    }

    /** READY·승인 중·결과 불명 상태도 읽지 않고 기존 주문·예약·정원을 그대로 보존한다. */
    @ParameterizedTest
    @ValueSource(strings = {"READY", "CONFIRMING", "UNKNOWN", "COMPLETED"})
    void preservesResourcesAndSkipsQueries(String paymentStatus) {
        RegistrationCreateResponse created = personal(categoryA, "S", "1990-01-01");
        if (paymentStatus.equals("COMPLETED")) {
            mockApprovalSuccess();
            payments.confirm(confirmRequest(created.paymentId()));
        } else {
            jdbc.update("update payment set process_status = ? where id = ?", paymentStatus, created.paymentId());
        }
        RegistrationModificationRequest request = request(created.registrationId(), categoryA, "S", "정정 이름", "정정 주소");
        // 기존 선택을 현재 신규 정책으로 재검증하면 실패하도록 바꾸어 추가 정책 조회를 감지한다.
        jdbc.update("update event_category set is_active = false where id = ?", categoryA);
        jdbc.update("update souvenir set is_active = false where id = ?", souvenirId);
        Map<String, List<Map<String, Object>>> resources = resources();
        Map<String, Object> before = registration(created.registrationId());
        SqlCapture.begin();
        RegistrationModificationSettlementResult result;
        List<String> sql;
        try {
            result = commands.modifyPersonal(eventId, created.registrationId(), request);
        } finally {
            sql = SqlCapture.end();
        }
        assertThat(result.orders()).isEmpty();
        assertThat(resources()).isEqualTo(resources);
        Map<String, Object> after = registration(created.registrationId());
        assertThat(after.get("name")).isEqualTo("정정 이름");
        assertThat(after.get("address")).isEqualTo("정정 주소");
        assertThat(withoutPersonalMetadata(after)).isEqualTo(withoutPersonalMetadata(before));
        assertNoFinancialOrPolicySql(sql);
    }

    /** NONE은 Registration version과 수정 시각까지 보존하고 예약·금융 SQL도 실행하지 않는다. */
    @Test
    void unchangedRequestHasNoWrites() {
        RegistrationCreateResponse created = personal(categoryA, "S", "1990-01-01");
        String name = s("select name from registration where id = ?", created.registrationId());
        RegistrationModificationRequest request = request(created.registrationId(), categoryA, "S", name, "테스트 주소");
        Map<String, Object> before = registration(created.registrationId());
        Map<String, List<Map<String, Object>>> resources = resources();
        SqlCapture.begin();
        List<String> sql;
        try {
            commands.modifyPersonal(eventId, created.registrationId(), request);
        } finally {
            sql = SqlCapture.end();
        }
        assertThat(registration(created.registrationId())).isEqualTo(before);
        assertThat(resources()).isEqualTo(resources);
        assertNoFinancialOrPolicySql(sql);
        assertThat(sql).noneMatch(statement -> statement.matches("(?is).*\\b(update|insert|delete)\\b.*"));
    }

    /** 실제 flush 후 후속 실패를 주입해 개인정보 UPDATE도 외부 명령 트랜잭션과 함께 롤백되는지 확인한다. */
    @Test
    void failureAfterFlushRollsBackInformation() {
        RegistrationCreateResponse created = personal(categoryA, "S", "1990-01-01");
        RegistrationModificationRequest request = request(created.registrationId(), categoryA, "S", "정정 이름", "정정 주소");
        Map<String, Object> before = registration(created.registrationId());
        Map<String, List<Map<String, Object>>> resources = resources();
        // 설정 시 MANDATORY 프록시를 호출하지 않는다. 실제 commands 호출은 기존 프록시를 그대로 통과한다.
        RegistrationPersonalInformationService target = AopTestUtils.getUltimateTargetObject(informationService);
        assertThat(mockingDetails(target).isSpy()).isTrue();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new CustomException(ErrorCode.INVALID_REGISTRATION_MODIFICATION_ARGUMENT);
        }).when(target).modify(any(RegistrationModificationAccessContext.class), any(RegistrationModificationClassifier.Change.class));
        expectError(ErrorCode.INVALID_REGISTRATION_MODIFICATION_ARGUMENT,
                () -> commands.modifyPersonal(eventId, created.registrationId(), request));
        assertThat(registration(created.registrationId())).isEqualTo(before);
        assertThat(resources()).isEqualTo(resources);
    }

    /** 서로 다른 개인정보/전체 수정이 사전 검사를 모두 통과해도 최종 uniqueInfo는 한 행만 차지한다. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentDifferentRegistrationsCannotClaimSameIdentity(boolean secondFull) throws Exception {
        RegistrationCreateResponse first = personal(categoryA, "S", "1990-01-01");
        RegistrationCreateResponse second = personal(categoryA, "S", "1990-01-01");
        RegistrationModificationRequest firstRequest = request(first.registrationId(), categoryA, "S", "동일 최종 이름", "정정 주소");
        RegistrationModificationRequest secondRequest = request(second.registrationId(), secondFull ? categoryB : categoryA,
                secondFull ? "M" : "S", "동일 최종 이름", "정정 주소");
        Map<String, Object> firstBefore = registration(first.registrationId());
        Map<String, Object> secondBefore = registration(second.registrationId());
        Map<String, List<Map<String, Object>>> resources = resources();
        CountDownLatch checked = new CountDownLatch(2);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            checked.countDown();
            assertThat(checked.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(uniqueInfo).validateOtherActive(eq(eventId), anyString(), eq("동일 최종 이름"), anyString(), anyString());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> a = executor.submit(() -> attempt(first.registrationId(), firstRequest));
            Future<Throwable> b = executor.submit(() -> attempt(second.registrationId(), secondRequest));
            Throwable aError = a.get(20, TimeUnit.SECONDS);
            Throwable bError = b.get(20, TimeUnit.SECONDS);
            assertThat((aError == null) ^ (bError == null)).isTrue();
            Throwable duplicate = aError == null ? bError : aError;
            assertThat(RegistrationUniqueConstraint.matches(duplicate)).isTrue();
            // 합성 예외 대신 실제 DB 경합 예외를 기존 HTTP 오류 변환기에 전달한다.
            assertThat(duplicate).isInstanceOf(DataIntegrityViolationException.class);
            ResponseEntity<ErrorResponse> response = new GlobalExceptionHandler()
                    .handleRegistrationUniqueConflict((DataIntegrityViolationException) duplicate);
            assertThat(response.getStatusCode()).isEqualTo(ErrorCode.REGISTRATION_ALREADY_EXISTS.getHttpStatus());
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.REGISTRATION_ALREADY_EXISTS.name());
            assertThat(registration(aError == null ? second.registrationId() : first.registrationId()))
                    .isEqualTo(aError == null ? secondBefore : firstBefore);
            if (!secondFull || bError != null) {
                assertThat(resources()).isEqualTo(resources);
            } else {
                assertThat(s("select event_category_id from registration where id = ?", second.registrationId())).isEqualTo(categoryB);
                counters(categoryACapacity, 1, 0);
                counters(categoryBCapacity, 1, 0);
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(n("select count(*) from registration where event_id = ? and name = ? and is_del = 0", eventId, "동일 최종 이름"))
                .isEqualTo(1);
    }

    /** 동일 uniqueInfo의 삭제 이력을 여러 건 보존하면서 활성 신청 정정은 허용한다. */
    @Test
    void multipleDeletedHistoriesDoNotBlockActiveIdentity() {
        RegistrationCreateResponse first = personal(categoryA, "S", "1990-01-01");
        RegistrationCreateResponse second = personal(categoryA, "S", "1990-01-01");
        RegistrationCreateResponse active = personal(categoryA, "S", "1990-01-01");
        jdbc.update("update registration set is_del = 1, name = ? where id in (?, ?)",
                "삭제 이력 이름", first.registrationId(), second.registrationId());
        commands.modifyPersonal(eventId, active.registrationId(), request(active.registrationId(), categoryA, "S", "삭제 이력 이름", "정정 주소"));
        assertThat(n("select count(*) from registration where event_id = ? and name = ?", eventId, "삭제 이력 이름")).isEqualTo(3);
        assertThat(n("select count(*) from registration where event_id = ? and name = ? and is_del = 0", eventId, "삭제 이력 이름")).isEqualTo(1);
    }

    /** 개인정보와 전체 수정이 같은 snapshot에서 출발할 때 먼저 커밋한 결과를 양쪽 순서 모두 보존한다. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void staleInformationOrFullRequestCannotOverwriteWinner(boolean informationWins) throws Exception {
        RegistrationCreateResponse created = personal(categoryA, "S", "1990-01-01");
        String id = created.registrationId();
        String name = s("select name from registration where id = ?", id);
        RegistrationModificationRequest stale = request(id, informationWins ? categoryB : categoryA,
                informationWins ? "M" : "S", name, "stale");
        RegistrationModificationRequest winner = request(id, informationWins ? categoryA : categoryB,
                informationWins ? "S" : "M", name, "winner");
        CountDownLatch classified = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            RegistrationModificationRequest candidate = invocation.getArgument(1);
            if (candidate.address().equals("stale")) {
                classified.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        }).when(classifier).classifyPersonal(any(Registration.class), any());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> staleResult = executor.submit(() -> attempt(id, stale));
            assertThat(classified.await(10, TimeUnit.SECONDS)).isTrue();
            commands.modifyPersonal(eventId, id, winner);
            Map<String, Object> winnerRegistration = registration(id);
            Map<String, List<Map<String, Object>>> winnerResources = resources();
            release.countDown();
            Throwable error = staleResult.get(20, TimeUnit.SECONDS);
            if (informationWins) {
                assertThat(error).isInstanceOfSatisfying(CustomException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION));
            } else {
                assertThat(error).isInstanceOf(ObjectOptimisticLockingFailureException.class);
            }
            assertThat(registration(id)).isEqualTo(winnerRegistration);
            assertThat(resources()).isEqualTo(winnerResources);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 1건의 변경 결과 또는 실패를 반환하되 오류 종류 판정은 호출 테스트가 수행한다. */
    private Throwable attempt(String registrationId, RegistrationModificationRequest request) {
        try {
            commands.modifyPersonal(eventId, registrationId, request);
            return null;
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    /** 기존 fixture의 인증과 정책값을 유지하고 변경할 개인 필드 및 선택만 지정한다. */
    private RegistrationModificationRequest request(String id, String category, String size, String name, String address) {
        return new RegistrationModificationRequest(new RegistrationAccessRequest(
                s("select name from registration where id = ?", id), "1990-01-01", "010-0000-0000", "Test1234!"),
                category, List.of(new SouvenirJson(souvenirId, size)), name, "010-0000-0000", "1990-01-01",
                GenderClass.M, address, "상세", "테스트 보호자", true);
    }

    /** 신청의 전체 저장값을 JSON 문자열과 함께 비교한다. */
    private Map<String, Object> registration(String id) {
        Map<String, Object> values = new LinkedHashMap<>(jdbc.queryForMap("select * from registration where id = ?", id));
        values.put("souvenir_json", s("select cast(souvenir_json as char) from registration where id = ?", id));
        return values;
    }

    /** 개인정보 변경이 허용된 필드와 정상적인 수정 메타데이터만 비교에서 제외한다. */
    private Map<String, Object> withoutPersonalMetadata(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        for (String field : List.of("name", "ph_num", "gender", "address", "address_detail", "version", "modified_at")) {
            result.remove(field);
        }
        return result;
    }

    /** 테스트 대회의 주문·배분·예약 상세·이력·만료·정원 행을 전후 비교한다. */
    private Map<String, List<Map<String, Object>>> resources() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        result.put("payment", jdbc.queryForList("select p.* from payment p join registration r on r.id=p.registration_id where r.event_id=? order by p.id", eventId));
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

    /** ORM이 발행한 실제 SQL에서 금지 테이블과 비관적 잠금이 없는지 확인한다. */
    private void assertNoFinancialOrPolicySql(List<String> statements) {
        assertThat(statements).isNotEmpty();
        for (String statement : statements) {
            String sql = statement.toLowerCase(Locale.ROOT);
            assertThat(sql).doesNotContain("for update", "for share");
            assertThat(sql).doesNotMatch("(?s).*\\b(payment|payment_allocation|reservation|reservation_item|capacity|capacity_category|event_registration_policy|event_category_registration_policy|event_category_souvenir|event_category_souvenir_policy|souvenir)\\b.*");
        }
    }

    /** 검증 구간의 현재 스레드 SQL만 메모리에 모으며 출력하거나 외부 저장하지 않는다. */
    public static class SqlCapture implements StatementInspector {
        private static final ThreadLocal<List<String>> SQL = new ThreadLocal<>();

        /** fixture와 검증용 JDBC 조회를 제외한 캡처를 시작한다. */
        static void begin() { SQL.set(new ArrayList<>()); }

        /** 수집한 SQL을 반환하고 스레드 상태를 해제한다. */
        static List<String> end() {
            List<String> result = List.copyOf(SQL.get());
            SQL.remove();
            return result;
        }

        /** Hibernate SQL을 변경하지 않고 관찰한다. */
        @Override
        public String inspect(String sql) {
            if (SQL.get() != null) { SQL.get().add(sql); }
            return sql;
        }
    }
}
