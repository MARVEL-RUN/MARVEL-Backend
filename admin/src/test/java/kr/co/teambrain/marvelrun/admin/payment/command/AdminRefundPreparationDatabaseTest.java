package kr.co.teambrain.marvelrun.admin.payment.command;

import jakarta.persistence.EntityManager;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import kr.co.teambrain.marvelrun.admin.payment.command.application.refund.*;
import kr.co.teambrain.marvelrun.admin.payment.command.infrastructure.toss.refund.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.*;
import kr.co.teambrain.marvelrun.admin.event.command.application.service.RegistrationPricingService;
import kr.co.teambrain.marvelrun.admin.event.command.application.valid.*;
import kr.co.teambrain.marvelrun.admin.event.command.application.valid.loader.RegistrationPolicyLoader;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.domain.*;
import kr.co.teambrain.marvelrun.admin.capacity.command.application.service.*;
import kr.co.teambrain.marvelrun.admin.payment.command.application.creator.*;
import kr.co.teambrain.marvelrun.admin.payment.command.application.domain.PaymentAllocation;
import kr.co.teambrain.marvelrun.admin.payment.command.dto.AdminPaymentPartialRefundTarget;
import kr.co.teambrain.marvelrun.common.inheritance_enum.*;
import kr.co.teambrain.marvelrun.common.inheritance_enum.capacity.ReservationStatus;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.*;
import kr.co.teambrain.marvelrun.common.json_object.SouvenirJson;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 실제 MySQL에서 JDBC 잠금과 JPA 준비 변경의 동일 트랜잭션·커밋·롤백을 검증한다. PG는 호출하지 않는다. */
@Tag("admin-refund-db")
@EnabledIfEnvironmentVariable(named = "MARVELRUN_ADMIN_REFUND_DB_TEST", matches = "true")
@ActiveProfiles("admin-refund-test")
@DataJpaTest(showSql = false, properties = {
        "spring.datasource.url=${MARVELRUN_TEST_DB_URL}",
        "spring.datasource.username=${MARVELRUN_TEST_DB_USERNAME}",
        "spring.datasource.password=${MARVELRUN_TEST_DB_PASSWORD}",
        "spring.jpa.hibernate.ddl-auto=none", "spring.sql.init.mode=never",
        "spring.flyway.enabled=false", "spring.liquibase.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=4"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({AdminRefundPreparationService.class, AdminRefundPreparationTransactionService.class, AdminRefundPreparationStore.class,
        AdminRefundAccessService.class, AdminRefundLockRepository.class, AdminRefundTime.class,
        RegistrationPolicyCandidateValidator.class, RegistrationPolicyValidator.class, RegistrationPolicyLoader.class,
        RegistrationPricingService.class, CapacityRequirementResolver.class, ReservationCapacityDiffService.class,
        CapacityModificationService.class, ReservationRemovalService.class, ModificationRefundPlanner.class,
        PaymentCancelAllocationCreator.class, RefundExecutionLock.class, RefundExecutionTransactionService.class,
        ModificationRefundExecutor.class, AdminRefundExecutionService.class, AdminRefundPreparationDatabaseTest.InputValidation.class})
class AdminRefundPreparationDatabaseTest {
    /** 슬라이스 테스트에서도 실제 Jakarta Validation을 사용한다. */
    @TestConfiguration
    static class InputValidation {
        @Bean(destroyMethod = "close") ValidatorFactory refundValidatorFactory() { return Validation.buildDefaultValidatorFactory(); }
        @Bean Validator refundValidator(ValidatorFactory factory) { return factory.getValidator(); }
    }
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired AdminRefundPreparationService service;
    @MockitoBean AdminRefundTime time;
    @MockitoBean TossPaymentCancelClient toss;
    @Autowired AdminRefundExecutionService execution;
    @MockitoSpyBean RefundExecutionTransactionService executionTransactions;
    @MockitoSpyBean AdminRefundPreparationStore store;
    private TransactionTemplate tx;
    private final LocalDateTime now = LocalDateTime.of(2026, 11, 2, 12, 0);
    private String eventId, categoryA, categoryB, souvenir, total, capacityA, capacityB, shirt, registrationId, paymentId, reservationId;

    /** 테스트 전용 UUID 대회만 생성한다. 만료된 기간에서도 관리자 환불이 가능한지 함께 검사한다. */
    @BeforeEach
    void fixture() {
        tx = new TransactionTemplate(manager);
        eventId = id(); categoryA = id(); categoryB = id(); souvenir = id();
        when(time.now()).thenReturn(now);
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    insert into event(id,name_kr,start_date,region,host,organizer,event_status,visible_status,
                    regist_start_date,regist_deadline,payment_deadline,auto_max_regist,auto_start,auto_deadline,phone_auth_required)
                    values(?,?,?,?,?,?,'CLOSED','OPEN',?,?,?,true,true,true,false)
                    """, eventId, "admin refund fixture", now.plusDays(10), "test", "test", "test",
                    now.minusDays(20), now.minusDays(10), now.minusDays(5));
            jdbc.update("insert into event_registration_policy(id,event_id,guardian_required_birth_from) values(?,?,?)",
                    id(), eventId, java.time.LocalDate.of(2012, 11, 13));
            category(categoryA, 70000); category(categoryB, 40000);
            jdbc.update("insert into souvenir(id,event_id,name,sizes,is_active,sort_order) values(?,?,?,'M',true,0)",
                    souvenir, eventId, "shirt");
            for (String category : List.of(categoryA, categoryB)) {
                jdbc.update("insert into event_category_souvenir(id,event_category_id,souvenir_id,created_at) values(?,?,?,?)",
                        id(), category, souvenir, now);
            }
            total = capacity("EVENT_TOTAL", null, "", 1);
            capacityA = capacity("CATEGORY", null, "", 1);
            capacityB = capacity("CATEGORY", null, "", 0);
            shirt = capacity("SOUVENIR", souvenir, "M", 1);
            jdbc.update("insert into capacity_category(id,capacity_id,event_category_id) values(?,?,?)", id(), capacityA, categoryA);
            jdbc.update("insert into capacity_category(id,capacity_id,event_category_id) values(?,?,?)", id(), capacityB, categoryB);
            Registration registration = Registration.builder().event(em.getReference(Event.class, eventId))
                    .eventCategory(em.getReference(EventCategory.class, categoryA)).name("test-"+id().substring(0,8))
                    .phNum("010-0000-0000").birth("1990-01-01").gender(GenderClass.M).password("Test1234!")
                    .souvenirJson(List.of(new SouvenirJson(souvenir,"M"))).status(RegistrationStatus.CONFIRMED)
                    .contractAmount(new BigDecimal("70000")).paidAmount(new BigDecimal("70000"))
                    .termsEssentialAgreed(true).termsMarketingAgreed(false).termsMarketingChannelAgreed(false)
                    .termsAgreedAt(now.minusDays(2)).build();
            em.persist(registration); registrationId = registration.getId();
            Reservation reservation = Reservation.builder().registration(registration).status(ReservationStatus.CONSUMED).build();
            em.persist(reservation); reservationId = reservation.getId();
            for (String c : List.of(total, capacityA, shirt)) {
                em.persist(ReservationItem.create(reservation, em.getReference(Capacity.class,c), 1));
            }
            Payment payment = Payment.builder().registration(registration).amount(new BigDecimal("70000"))
                    .orderId("test-"+id()).orderName("test refund").purpose(PaymentPurpose.REGISTRATION_TRY)
                    .processStatus(PaymentProcessStatus.COMPLETED).tossStatus(TossPaymentStatus.DONE)
                    .paymentKey("fixture-"+id()).confirmIdempotencyKey(id()).approvedAt(now.minusDays(1)).build();
            em.persist(payment); paymentId = payment.getId();
            em.persist(PaymentAllocation.create(payment,registration,new BigDecimal("70000")));
            em.flush();
        });
    }

    /** 같은 트랜잭션에 계약·예약 상세·카운터·시도·귀속·로그가 함께 저장된다. */
    @Test
    void partialRefundMovesConfirmedResourcesAndCreatesLedger() {
        AdminRefundPrepared result = service.preparePartial(eventId,null,List.of(target(true)),command());
        assertThat(result.refunds()).hasSize(1);
        assertThat(result.refunds().getFirst().amount()).isEqualByComparingTo("30000");
        assertThat(amount("contract_amount")).isEqualByComparingTo("40000");
        assertThat(amount("paid_amount")).isEqualByComparingTo("70000");
        assertThat(state()).isEqualTo("PARTIAL_REFUND_REQUIRED");
        assertThat(count(total)).isEqualTo(1); assertThat(count(capacityA)).isZero(); assertThat(count(capacityB)).isEqualTo(1);
        assertThat(count(shirt)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from reservation where id=?",String.class,reservationId)).isEqualTo("CONSUMED");
        assertThat(cancelCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select sum(ca.allocated_amount) from payment_cancel_allocation ca join payment_cancel c on c.id=ca.payment_cancel_id where c.payment_id=?",BigDecimal.class,paymentId)).isEqualByComparingTo("30000");
        assertThat(jdbc.queryForObject("select source from payment_process_log where payment_id=?",String.class,paymentId)).isEqualTo("ADMIN");
        assertThat(result.preparedAt()).isEqualTo(now);
        assertThatThrownBy(() -> service.preparePartial(eventId,null,List.of(target(true)),command()))
                .isInstanceOfSatisfying(kr.co.teambrain.marvelrun.admin.common.exception.CustomException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode.PAYMENT_CANCEL_CONFLICT));
        assertThat(cancelCount()).isEqualTo(1);
        executeSuccess(result);
        assertThat(amount("paid_amount")).isEqualByComparingTo("40000");
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(count(total)).isEqualTo(1);
        // 두 번째 환불은 첫 환불 거래를 차감한 원결제 잔액 40000원만 사용한다.
        AdminRefundPrepared remaining = service.prepareFull(eventId,null,List.of(registrationId),command());
        assertThat(remaining.refunds().getFirst().amount()).isEqualByComparingTo("40000");
        executeSuccess(remaining);
        assertThat(amount("paid_amount")).isEqualByComparingTo("0");
        assertThat(state()).isEqualTo("CANCELED");
    }

    /** 전액 취소는 정원을 한 번 반환하고 실제 납부액은 결과 반영 전까지 보존한다. */
    @Test
    void fullRefundReleasesAndLeavesPaidAmount() {
        AdminRefundPrepared result = service.prepareFull(eventId,null,List.of(registrationId),command());
        assertThat(amount("contract_amount")).isEqualByComparingTo("0");
        assertThat(amount("paid_amount")).isEqualByComparingTo("70000");
        assertThat(state()).isEqualTo("CANCELLATION_PENDING");
        assertThat(count(total)).isZero(); assertThat(count(capacityA)).isZero(); assertThat(count(shirt)).isZero();
        assertThat(jdbc.queryForObject("select status from reservation where id=?",String.class,reservationId)).isEqualTo("RELEASED");
        assertThat(cancelCount()).isEqualTo(1);
        executeSuccess(result);
        assertThat(amount("paid_amount")).isEqualByComparingTo("0");
        assertThat(state()).isEqualTo("CANCELED");
        assertThat(count(total)).isZero();
    }

    /** 현재 가격표가 0원인 후보에서도 사전에 지정한 참가 유지 선택을 실제 DB에 반영한다. */
    @ParameterizedTest
    @ValueSource(booleans = {true,false})
    void zeroContractKeepsOrReleasesParticipation(boolean keep) {
        jdbc.update("update event_category set amount=0 where id=?",categoryB);
        AdminRefundPrepared result = service.preparePartial(eventId,null,List.of(target(keep)),command());
        assertThat(amount("contract_amount")).isEqualByComparingTo("0");
        assertThat(amount("paid_amount")).isEqualByComparingTo("70000");
        assertThat(count(total)).isEqualTo(keep ? 1 : 0);
        assertThat(state()).isEqualTo(keep ? "PARTIAL_REFUND_REQUIRED" : "CANCELLATION_PENDING");
        assertThat(jdbc.queryForObject("select expires_at from reservation where id=?",java.sql.Timestamp.class,reservationId)).isNull();
        executeSuccess(result);
        assertThat(amount("paid_amount")).isEqualByComparingTo("0");
        assertThat(state()).isEqualTo(keep ? "CONFIRMED" : "CANCELED");
        assertThat(count(total)).isEqualTo(keep ? 1 : 0);
    }

    /** 로그 저장 실패가 계약·정원·환불 시도·귀속 전체를 원복시키는지 실제 트랜잭션으로 검증한다. */
    @Test
    void failureAfterWritesRollsBackWholePreparation() {
        // 실패 설정 시에만 프록시 내부 spy에 접근하며, 실제 준비 실행은 서비스 프록시를 통한다.
        AdminRefundPreparationStore storeSpy = AopTestUtils.getUltimateTargetObject(store);
        assertThat(mockingDetails(storeSpy).isSpy()).isTrue();
        doThrow(new IllegalStateException("injected log failure")).when(storeSpy).log(any());
        assertThatThrownBy(() -> service.preparePartial(eventId,null,List.of(target(true)),command()))
                .isInstanceOf(IllegalStateException.class).hasMessage("injected log failure");
        assertThat(amount("contract_amount")).isEqualByComparingTo("70000");
        assertThat(amount("paid_amount")).isEqualByComparingTo("70000");
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(count(total)).isEqualTo(1); assertThat(count(capacityA)).isEqualTo(1); assertThat(count(capacityB)).isZero();
        assertThat(cancelCount()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payment_process_log where payment_id=?",Integer.class,paymentId)).isZero();
        assertThat(jdbc.queryForObject("select version from registration where id=?",Long.class,registrationId)).isZero();
    }

    /** 단체 일부·전체 취소에서 선택하지 않은 인원의 계약과 원귀속을 사용하지 않는다. */
    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true"})
    void groupFullRefundRespectsSelectedMembers(boolean whole, boolean mixedReady) {
        String organizationId = id();
        String secondId = tx.execute(status -> {
            jdbc.update("insert into organization(id,event_id,login_id,password,group_name,leader_name,leader_birth,leader_ph_num,guardian_consent,created_at) values(?,?,?,?,?,?,?,?,true,?)",
                    organizationId,eventId,"g"+id().substring(0,15),"Test1234!","fixture group","leader","1990-01-01","010-0000-0000",now);
            jdbc.update("update registration set organization_id=? where id=?",organizationId,registrationId);
            jdbc.update("update payment set registration_id=null,organization_id=?,amount=140000 where id=?",organizationId,paymentId);
            Registration second = Registration.builder().event(em.getReference(Event.class,eventId))
                    .organization(em.getReference(kr.co.teambrain.marvelrun.admin.user.command.application.domain.Organization.class,organizationId))
                    .eventCategory(em.getReference(EventCategory.class,categoryA)).name("second-"+id().substring(0,8))
                    .phNum("010-0000-0000").birth("1990-01-01").gender(GenderClass.M).password("Test1234!")
                    .souvenirJson(List.of(new SouvenirJson(souvenir,"M"))).status(RegistrationStatus.CONFIRMED)
                    .contractAmount(new BigDecimal("70000")).paidAmount(new BigDecimal("70000"))
                    .termsEssentialAgreed(true).termsMarketingAgreed(false).termsMarketingChannelAgreed(false)
                    .termsAgreedAt(now.minusDays(2)).build();
            em.persist(second);
            Reservation v = Reservation.builder().registration(second).status(ReservationStatus.CONSUMED).build(); em.persist(v);
            for (String capacity : List.of(total,capacityA,shirt)) {
                em.persist(ReservationItem.create(v,em.getReference(Capacity.class,capacity),1));
                jdbc.update("update capacity set confirmed_count=confirmed_count+1 where id=?",capacity);
            }
            em.persist(PaymentAllocation.create(em.getReference(Payment.class,paymentId),second,new BigDecimal("70000")));
            Payment ready = Payment.builder().organization(em.getReference(kr.co.teambrain.marvelrun.admin.user.command.application.domain.Organization.class,organizationId))
                    .amount(new BigDecimal(mixedReady ? "20000" : "10000")).orderId("ready-"+id()).orderName("ready fixture")
                    .purpose(PaymentPurpose.ADDITIONAL_PAYMENT).processStatus(PaymentProcessStatus.READY).confirmIdempotencyKey(id()).build();
            em.persist(ready);
            em.persist(PaymentAllocation.create(ready,second,new BigDecimal("10000")));
            if (mixedReady) { em.persist(PaymentAllocation.create(ready,em.getReference(Registration.class,registrationId),new BigDecimal("10000"))); }
            em.flush(); return second.getId();
        });
        String readyId = jdbc.queryForObject("select id from payment where organization_id=? and process_status='READY'",String.class,organizationId);
        if (mixedReady) {
            assertThatThrownBy(() -> service.prepareFull(eventId,organizationId,List.of(registrationId),command()))
                    .isInstanceOfSatisfying(kr.co.teambrain.marvelrun.admin.common.exception.CustomException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo(kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode.REGISTRATION_MODIFICATION_PAYMENT_CONFLICT));
            assertThat(state()).isEqualTo("CONFIRMED"); assertThat(count(total)).isEqualTo(2); assertThat(cancelCount()).isZero();
            assertThat(jdbc.queryForObject("select process_status from payment where id=?",String.class,readyId)).isEqualTo("READY");
            return;
        }
        AdminRefundPrepared result = service.prepareFull(eventId,organizationId,whole ? List.of(registrationId,secondId) : List.of(registrationId),command());
        assertThat(result.refunds()).hasSize(1);
        assertThat(result.refunds().getFirst().amount()).isEqualByComparingTo(whole ? "140000" : "70000");
        assertThat(jdbc.queryForObject("select process_status from payment where id=?",String.class,readyId))
                .isEqualTo(whole ? "INVALIDATED" : "READY");
        assertThat(count(total)).isEqualTo(whole ? 0 : 1);
        assertThat(state()).isEqualTo("CANCELLATION_PENDING");
        assertThat(jdbc.queryForObject("select status from registration where id=?",String.class,secondId))
                .isEqualTo(whole ? "CANCELLATION_PENDING" : "CONFIRMED");
        assertThat(jdbc.queryForObject("select paid_amount from registration where id=?",BigDecimal.class,secondId)).isEqualByComparingTo("70000");
        executeSuccess(result);
        assertThat(state()).isEqualTo("CANCELED");
        assertThat(amount("paid_amount")).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select paid_amount from registration where id=?",BigDecimal.class,secondId))
                .isEqualByComparingTo(whole ? "0" : "70000");
        assertThat(jdbc.queryForObject("select status from registration where id=?",String.class,secondId))
                .isEqualTo(whole ? "CANCELED" : "CONFIRMED");
        assertThat(count(total)).isEqualTo(whole ? 0 : 1);
    }

    /** 이동할 종목의 잔여 정원이 없으면 예약 이력 변경도 전체 롤백된다. */
    @Test
    void exhaustedCapacityRollsBackBeforeRefundPreparation() {
        jdbc.update("update capacity set limit_count=0 where id=?",capacityB);
        assertThatThrownBy(() -> service.preparePartial(eventId,null,List.of(target(true)),command()))
                .isInstanceOfSatisfying(kr.co.teambrain.marvelrun.admin.common.exception.CustomException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode.CAPACITY_ACQUIRE_FAILED));
        assertThat(amount("contract_amount")).isEqualByComparingTo("70000");
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(count(capacityA)).isEqualTo(1); assertThat(count(capacityB)).isZero();
        assertThat(cancelCount()).isZero();
        assertThat(jdbc.queryForObject("select version from reservation where id=?",Long.class,reservationId)).isZero();
    }

    /** 거절과 통신 결과불명은 준비 금액·정원을 보존하며 외부 요청을 다시 보내지 않는다. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectedOrUnknownDoesNotDeductPaidAmount(boolean rejected) {
        AdminRefundPrepared prepared = service.preparePartial(eventId,null,List.of(target(true)),command());
        TossCancelOutcome outcome = rejected ? TossCancelOutcome.rejected(401, "UNAUTHORIZED_KEY")
                : TossCancelOutcome.unknown(null, "TOSS_CANCEL_RESPONSE_UNAVAILABLE");
        doAnswer(invocation -> {
            assertCommittedStart(invocation.getArgument(0));
            return outcome;
        }).when(toss).cancel(any());
        AdminRefundExecutionService.Result result = execution.execute(prepared);
        assertThat(result.refunds().getFirst().outcomeStored()).isTrue();
        assertThat(cancelStatus(prepared)).isEqualTo(rejected ? "FAILED" : "UNKNOWN");
        assertThat(amount("paid_amount")).isEqualByComparingTo("70000");
        assertThat(amount("contract_amount")).isEqualByComparingTo("40000");
        assertThat(state()).isEqualTo("PARTIAL_REFUND_REQUIRED");
        assertThat(count(total)).isEqualTo(1); assertThat(count(capacityB)).isEqualTo(1);
        execution.execute(prepared);
        verify(toss, times(1)).cancel(any());
        assertThat(jdbc.queryForObject("select JSON_UNQUOTE(JSON_EXTRACT(metadata,'$.resultComparison.status')) from payment_process_log where payment_cancel_id=? and process_type=?",String.class,prepared.refunds().getFirst().paymentCancelId(), rejected ? "CANCEL_FAILED" : "CANCEL_UNKNOWN"))
                .isEqualTo(rejected ? "FAILED" : "UNVERIFIED");
    }

    /** 실제 결과 반영 후 예외를 주입하여 DB 반영 전체의 롤백과 성공 증거 보존을 검증한다. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void externalSuccessAndDatabaseFailurePreservesEvidence(boolean fallbackFails) {
        AdminRefundPrepared prepared = service.preparePartial(eventId,null,List.of(target(true)),command());
        RefundExecutionTransactionService spy = AopTestUtils.getUltimateTargetObject(executionTransactions);
        assertThat(mockingDetails(spy).isSpy()).isTrue();
        doAnswer(invocation -> {
            TossCancelOutcome outcome = invocation.getArgument(1);
            invocation.callRealMethod();
            if (outcome.kind() == TossCancelOutcome.Kind.VERIFIED || fallbackFails) {
                throw new IllegalStateException("injected result commit failure");
            }
            return null;
        }).when(spy).apply(any(), any());
        doAnswer(invocation -> {
            TossCancelAttempt attempt = invocation.getArgument(0);
            assertCommittedStart(attempt);
            return verifiedOutcome(attempt);
        }).when(toss).cancel(any());
        AdminRefundExecutionService.Result result = execution.execute(prepared);
        AdminRefundExecutionResult item = result.refunds().getFirst();
        assertThat(item.outcomeStored()).isFalse();
        assertThat(item.unknownStored()).isEqualTo(!fallbackFails);
        assertThat(item.externalOutcome().kind()).isEqualTo(TossCancelOutcome.Kind.VERIFIED);
        assertThat(cancelStatus(prepared)).isEqualTo(fallbackFails ? "PROCESSING" : "UNKNOWN");
        assertThat(amount("paid_amount")).isEqualByComparingTo("70000");
        assertThat(amount("contract_amount")).isEqualByComparingTo("40000");
        assertThat(state()).isEqualTo("PARTIAL_REFUND_REQUIRED");
        assertThat(count(total)).isEqualTo(1); assertThat(count(capacityB)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from payment_process_log where payment_id=? and process_type='CANCEL_SUCCEEDED'",Integer.class,paymentId)).isZero();
        if (!fallbackFails) {
            assertThat(jdbc.queryForObject("select JSON_UNQUOTE(JSON_EXTRACT(metadata,'$.resultComparison.status')) from payment_process_log where payment_cancel_id=? and process_type='CANCEL_UNKNOWN'",String.class,prepared.refunds().getFirst().paymentCancelId())).isEqualTo("MISMATCH");
            assertThat(jdbc.queryForObject("select transaction_key from payment_process_log where payment_id=? and process_type='CANCEL_UNKNOWN'",String.class,paymentId)).isEqualTo(item.externalOutcome().cancellation().transactionKey());
            assertThat(jdbc.queryForObject("select JSON_UNQUOTE(JSON_EXTRACT(metadata,'$.externalPaymentStatus')) from payment_process_log where payment_id=? and process_type='CANCEL_UNKNOWN'",String.class,paymentId)).isEqualTo("PARTIAL_CANCELED");
        }
        execution.execute(prepared);
        verify(toss, times(1)).cancel(any());
    }

    /** 시작 기록 트랜잭션이 롤백되면 PG를 호출하지 않는다. */
    @Test
    void beginRollbackNeverCallsToss() {
        AdminRefundPrepared prepared = service.prepareFull(eventId,null,List.of(registrationId),command());
        RefundExecutionTransactionService spy = AopTestUtils.getUltimateTargetObject(executionTransactions);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("injected begin commit failure");
        }).when(spy).begin(any(), isNull(), any());
        AdminRefundExecutionService.Result result = execution.execute(prepared);
        assertThat(result.refunds().getFirst().errorCode()).isEqualTo("CANCEL_BEGIN_FAILED");
        verifyNoInteractions(toss);
        assertThat(jdbc.queryForObject("select requested_at from payment_cancel where id=?",java.sql.Timestamp.class,prepared.refunds().getFirst().paymentCancelId())).isNull();
        assertThat(amount("paid_amount")).isEqualByComparingTo("70000");
    }

    /** 동일 성공 결과의 중복 반영은 순납부액과 성공 로그를 두 번 변경하지 않는다. */
    @Test
    void duplicateSuccessApplicationIsIdempotent() {
        AdminRefundPrepared prepared = service.preparePartial(eventId,null,List.of(target(true)),command());
        AdminRefundPrepared.Refund refund = prepared.refunds().getFirst();
        AdminRefundExecutionRequest.Refund request = new AdminRefundExecutionRequest.Refund(
                refund.paymentCancelId(),refund.paymentId(),refund.amount(),refund.status(),prepared.correlationId());
        RefundExecutionTicket ticket = executionTransactions.begin(eventId,null,request).orElseThrow();
        TossCancelOutcome outcome = verifiedOutcome(ticket.attempt());
        executionTransactions.apply(ticket,outcome);
        executionTransactions.apply(ticket,outcome);
        assertThat(amount("paid_amount")).isEqualByComparingTo("40000");
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("select count(*) from payment_process_log where payment_cancel_id=? and process_type='CANCEL_SUCCEEDED'",Integer.class,refund.paymentCancelId())).isEqualTo(1);
        verifyNoInteractions(toss);
    }

    /** 검증된 외부 결과를 주입하고 실제 시작·반영 트랜잭션과 중복 전송 차단을 검증한다. */
    private void executeSuccess(AdminRefundPrepared prepared) {
        clearInvocations(toss);
        doAnswer(invocation -> {
            TossCancelAttempt attempt = invocation.getArgument(0);
            assertCommittedStart(attempt);
            return verifiedOutcome(attempt);
        }).when(toss).cancel(any());
        AdminRefundExecutionService.Result result = execution.execute(prepared);
        assertThat(result.preparedAt()).isEqualTo(now);
        assertThat(result.finishedAt()).isEqualTo(now);
        assertThat(result.refunds()).hasSize(1);
        assertThat(result.refunds().getFirst().outcomeStored()).isTrue();
        assertThat(cancelStatus(prepared)).isEqualTo("DONE");
        String cancelId = prepared.refunds().getFirst().paymentCancelId();
        assertThat(jdbc.queryForObject("select count(*) from payment_process_log where payment_cancel_id=? and source='ADMIN' and process_type='CANCEL_SUCCEEDED'",Integer.class,cancelId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select toss_status from payment where id=?",String.class,prepared.refunds().getFirst().paymentId()))
                .isEqualTo(result.refunds().getFirst().externalOutcome().cancellation().paymentStatus());
        AdminRefundExecutionService.Result duplicate = execution.execute(prepared);
        assertThat(duplicate.refunds().getFirst().started()).isFalse();
        verify(toss, times(1)).cancel(any());
        assertThat(jdbc.queryForObject("select JSON_UNQUOTE(JSON_EXTRACT(metadata,'$.resultComparison.status')) from payment_process_log where payment_cancel_id=? and process_type='CANCEL_SUCCEEDED'",String.class,cancelId)).isEqualTo("SUCCESS");
    }

    /** HTTP 대체 호출 시 트랜잭션이 없고 시작 기록은 다른 연결에서 보여야 한다. */
    private void assertCommittedStart(TossCancelAttempt attempt) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(jdbc.queryForObject("select requested_at from payment_cancel where id=?",java.sql.Timestamp.class,attempt.paymentCancelId())).isNotNull();
    }

    /** 이번 시도와 이미 완료된 원결제 취소를 반영한 성공 증거만 구성한다. */
    private TossCancelOutcome verifiedOutcome(TossCancelAttempt attempt) {
        BigDecimal balance = attempt.originalAmount().subtract(attempt.cancelAmount());
        for (BigDecimal previous : attempt.completedCancels().values()) { balance = balance.subtract(previous); }
        return TossCancelOutcome.verified(new VerifiedTossCancellation("tx-"+attempt.paymentCancelId(),attempt.cancelAmount(),balance,
                now.atOffset(ZoneOffset.ofHours(9)),balance.signum()==0 ? "CANCELED" : "PARTIAL_CANCELED"));
    }

    /** 실제 저장된 취소 상태를 1차 캐시 없이 읽는다. */
    private String cancelStatus(AdminRefundPrepared prepared) {
        return jdbc.queryForObject("select status from payment_cancel where id=?",String.class,prepared.refunds().getFirst().paymentCancelId());
    }

    /** 생성한 UUID 대회의 데이터만 FK 역순으로 정리한다. 실제 대회 ID는 사용하지 않는다. */
    @AfterEach
    void cleanup() {
        if (eventId == null || tx == null) return;
        tx.executeWithoutResult(status -> {
            List<String> paymentIds = jdbc.queryForList("select p.id from payment p where p.registration_id in(select id from registration where event_id=?) or p.organization_id in(select id from organization where event_id=?)",String.class,eventId,eventId);
            for (String id : paymentIds) {
                jdbc.update("delete from payment_process_log where payment_id=?",id);
                jdbc.update("delete ca from payment_cancel_allocation ca join payment_cancel c on c.id=ca.payment_cancel_id where c.payment_id=?",id);
                jdbc.update("delete from payment_cancel where payment_id=?",id);
                jdbc.update("delete from payment_allocation where payment_id=?",id);
                jdbc.update("delete from payment where id=?",id);
            }
            jdbc.update("delete i from reservation_item i join reservation v on v.id=i.reservation_id join registration r on r.id=v.registration_id where r.event_id=?",eventId);
            jdbc.update("delete v from reservation v join registration r on r.id=v.registration_id where r.event_id=?",eventId);
            jdbc.update("delete from registration where event_id=?",eventId);
            jdbc.update("delete cc from capacity_category cc join capacity c on c.id=cc.capacity_id where c.event_id=?",eventId);
            jdbc.update("delete from capacity where event_id=?",eventId);
            jdbc.update("delete ecs from event_category_souvenir ecs join event_category ec on ec.id=ecs.event_category_id where ec.event_id=?",eventId);
            jdbc.update("delete from souvenir where event_id=?",eventId);
            jdbc.update("delete p from event_category_registration_policy p join event_category ec on ec.id=p.event_category_id where ec.event_id=?",eventId);
            jdbc.update("delete from event_category where event_id=?",eventId);
            jdbc.update("delete from event_registration_policy where event_id=?",eventId);
            jdbc.update("delete from organization where event_id=?",eventId);
            jdbc.update("delete from event where id=?",eventId);
        });
    }

    /** 대회별 종목과 제한 없는 출생일 정책을 생성한다. */
    private void category(String category,int price) {
        jdbc.update("insert into event_category(id,event_id,amount,name,is_active,sort_order) values(?,?,?,'test',true,0)",category,eventId,price);
        jdbc.update("insert into event_category_registration_policy(id,event_category_id,allowed_birth_from,allowed_birth_to) values(?,?,null,null)",id(),category);
    }
    /** 사용자 테스트와 같은 자원 구조로 현재 확정 수량을 구성한다. */
    private String capacity(String type,String gift,String size,int confirmed) {
        String c=id();
        jdbc.update("insert into capacity(id,event_id,type,resource_key,name,souvenir_id,size,limit_count,held_count,confirmed_count,active,created_at,updated_at) values(?,?,?,?,?,?,?,20,0,?,true,?,?)",
                c,eventId,type,"TEST:"+c,"test",gift,size,confirmed,now,now); return c;
    }
    /** fixture 간 충돌을 피할 식별자를 만든다. */
    private String id() { return UUID.randomUUID().toString(); }
    /** 인증된 관리자 진입점의 추적정보를 대신한다. */
    private AdminRefundCommandContext command() { return new AdminRefundCommandContext(id(),"fixture-admin","test refund"); }
    /** 수기 금액 없이 종목 후보를 만든다. */
    private AdminPaymentPartialRefundTarget target(boolean keep) { return new AdminPaymentPartialRefundTarget(registrationId,categoryB,List.of(new SouvenirJson(souvenir,"M")),keep); }
    /** 테스트 내부에서 지정한 금액 컬럼을 조회한다. */
    private BigDecimal amount(String column) { return jdbc.queryForObject("select "+column+" from registration where id=?",BigDecimal.class,registrationId); }
    /** 실제 DB의 참가 상태를 조회한다. */
    private String state() { return jdbc.queryForObject("select status from registration where id=?",String.class,registrationId); }
    /** 실제 DB의 확정 정원을 조회한다. */
    private int count(String capacity) { return jdbc.queryForObject("select confirmed_count from capacity where id=?",Integer.class,capacity); }
    /** 이번 fixture 원결제의 취소 시도 수를 조회한다. */
    private int cancelCount() { return jdbc.queryForObject("select count(*) from payment_cancel where payment_id=?",Integer.class,paymentId); }
}
