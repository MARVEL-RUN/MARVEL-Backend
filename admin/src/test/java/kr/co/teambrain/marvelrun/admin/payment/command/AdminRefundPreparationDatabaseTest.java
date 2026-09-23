package kr.co.teambrain.marvelrun.admin.payment.command;

import jakarta.persistence.EntityManager;
import kr.co.teambrain.marvelrun.admin.event.command.application.service.AdminUnpaidRegistrationCancellationService;
import kr.co.teambrain.marvelrun.admin.event.command.application.service.RegistrationCommandService;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.teambrain.marvelrun.admin.payment.command.batch.*;
import kr.co.teambrain.marvelrun.admin.payment.command.batch.AdminRefundBatchModels.*;
import kr.co.teambrain.marvelrun.admin.payment.command.evidence.*;
import kr.co.teambrain.marvelrun.admin.payment.command.evidence.AdminRefundEvidenceModels;
import kr.co.teambrain.marvelrun.admin.payment.query.AdminPaymentQueryRepository;
import kr.co.teambrain.marvelrun.admin.payment.command.dto.AdminPaymentRefundRequest;
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
@Import({AdminUnpaidRegistrationCancellationService.class, RegistrationCommandService.class, AdminRefundEvidenceStore.class, AdminRefundEvidenceService.class, AdminRefundEvidenceMatcher.class, AdminPaymentQueryRepository.class, AdminRefundPreparationService.class, AdminRefundPreparationTransactionService.class, AdminRefundPreparationStore.class,
        AdminRefundAccessService.class, AdminRefundLockRepository.class, AdminRefundTime.class,
        RegistrationPolicyCandidateValidator.class, RegistrationPolicyValidator.class, RegistrationPolicyLoader.class,
        RegistrationPricingService.class, CapacityRequirementResolver.class, ReservationCapacityDiffService.class,
        CapacityModificationService.class, ReservationRemovalService.class, ModificationRefundPlanner.class,
        PaymentCancelAllocationCreator.class, RefundExecutionLock.class, RefundExecutionTransactionService.class,
        ModificationRefundExecutor.class, AdminRefundExecutionService.class,
        AdminRefundBatchStore.class, AdminRefundBatchSelection.class, AdminRefundBatchService.class, AdminRefundBatchWorker.class, AdminRefundPreparationDatabaseTest.InputValidation.class})
class AdminRefundPreparationDatabaseTest {
    /** 슬라이스 테스트에서도 실제 Jakarta Validation을 사용한다. */
    @TestConfiguration
    static class InputValidation {
        @Bean ObjectMapper refundBatchObjectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean(destroyMethod = "close") ValidatorFactory refundValidatorFactory() { return Validation.buildDefaultValidatorFactory(); }
        @Bean Validator refundValidator(ValidatorFactory factory) { return factory.getValidator(); }
    }
    @Autowired RegistrationCommandService registrationCommands;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired AdminRefundPreparationService service;
    @MockitoBean AdminRefundTime time;
    @MockitoBean TossPaymentCancelClient toss;
    @Autowired AdminRefundExecutionService execution;
    @Autowired AdminRefundBatchService batches;
    @Autowired AdminRefundEvidenceStore evidenceStore;
    @Autowired AdminRefundEvidenceService evidenceService;
    @MockitoBean AdminRefundEvidenceClient evidenceClient;
    @Autowired AdminRefundBatchStore batchStore;
    @Autowired AdminRefundBatchWorker batchWorker;
    @Autowired AdminRefundBatchSelection batchSelection;
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
        AdminRefundPrepared result = service.preparePartial(eventId,null,List.of(birthTarget()),command());
        assertThat(jdbc.queryForObject("select birth from registration where id=?",String.class,registrationId)).isEqualTo("1991-01-01");
        assertThat(jdbc.queryForObject("select ph_num from registration where id=?",String.class,registrationId)).isEqualTo("010-0000-0000");
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
        assertThatThrownBy(() -> service.preparePartial(eventId,null,List.of(birthTarget()),command()))
                .isInstanceOf(IllegalStateException.class).hasMessage("injected log failure");
        assertThat(jdbc.queryForObject("select birth from registration where id=?",String.class,registrationId)).isEqualTo("1990-01-01");
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
        List<Target> expanded=batchSelection.full(eventId,new AdminPaymentRefundRequest(id(),"중복 선택 확인",List.of(registrationId),List.of(organizationId)));
        assertThat(expanded).hasSize(2);
        assertThat(expanded.stream().map(Target::registrationId)).containsExactlyInAnyOrder(registrationId,secondId);
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
            jdbc.update("delete from admin_refund_evidence where event_id=?",eventId);
            jdbc.update("delete i from admin_refund_batch_item i join admin_refund_batch b on b.id=i.batch_id where b.event_id=?",eventId);
            jdbc.update("delete from admin_refund_batch where event_id=?",eventId);
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
    /** 생년월일 변경 후보를 기존 성공·롤백 검증에서 재사용한다. */
    private AdminPaymentPartialRefundTarget birthTarget() {
        return new AdminPaymentPartialRefundTarget(registrationId,categoryB,List.of(new SouvenirJson(souvenir,"M")),"1991-01-01",true);
    }

    /** 접수·중복·없는 대상·외부 실행·예외 필터를 실제 DB에서 한 흐름으로 확인한다. */
    @Test
    void batchPersistsDeduplicatesAndContinuesPastMissingTarget() {
        String requestId=id();
        AdminPaymentRefundRequest request=new AdminPaymentRefundRequest(requestId,"배치 환불",List.of(registrationId,"missing-"+id().substring(0,8)),List.of());
        doAnswer(invocation -> { TossCancelAttempt attempt=invocation.getArgument(0); assertCommittedStart(attempt); return verifiedOutcome(attempt); }).when(toss).cancel(any());
        Response response=batches.full(eventId,"fixture-admin",request);
        Summary accepted=response.summary();
        assertThat(response.items()).hasSize(2);
        assertThat(response.resultsTruncated()).isFalse();
        assertThat(jdbc.queryForObject("select JSON_UNQUOTE(JSON_EXTRACT(metadata,'$.batchId')) from payment_process_log where payment_id=? and process_type='CANCEL_PREPARED'",
                String.class,paymentId)).isEqualTo(accepted.batchId());
        assertThat(accepted.total()).isEqualTo(2);
        assertThat(accepted.counts()).containsEntry("BLOCKED",1L).containsEntry("SUCCEEDED",1L);
        assertThat(batches.full(eventId,"fixture-admin",request).summary().batchId()).isEqualTo(accepted.batchId());
        assertThatThrownBy(() -> batches.full(eventId,"fixture-admin",new AdminPaymentRefundRequest(requestId,"다른 사유",request.registrationIds(),List.of())))
                .isInstanceOf(kr.co.teambrain.marvelrun.admin.common.exception.CustomException.class);
        batchWorker.processOne(accepted.batchId());
        batchWorker.processOne(accepted.batchId());
        Summary finished=batchStore.summary(eventId,accepted.batchId());
        assertThat(finished.status()).isEqualTo("COMPLETED");
        assertThat(finished.counts()).containsEntry("SUCCEEDED",1L).containsEntry("BLOCKED",1L);
        assertThat(batchStore.items(eventId,accepted.batchId(),0,20,true).total()).isEqualTo(1);
        assertThat(amount("paid_amount")).isEqualByComparingTo("0");
        assertThat(batchStore.items(eventId,accepted.batchId(),0,20,false).items().stream()
                .filter(item -> item.result()!=null).findFirst().orElseThrow().result().toString()).doesNotContain("transactionKey","paymentKey");
        assertThatThrownBy(() -> batchStore.items(eventId,accepted.batchId(),0,101,false)).isInstanceOf(kr.co.teambrain.marvelrun.admin.common.exception.CustomException.class);
        verify(toss,times(1)).cancel(any());
        assertThatThrownBy(() -> batchStore.summary("other-event",accepted.batchId())).isInstanceOf(kr.co.teambrain.marvelrun.admin.common.exception.CustomException.class);
    }

    /** 접수 후 일반 정보 수정 등으로 버전이 바뀌면 예전 후보로 환불하지 않는다. */
    @Test
    void batchRejectsChangedRegistrationWithoutCallingToss() {
        Summary accepted=stageBatch();
        jdbc.update("update registration set version=version+1 where id=?",registrationId);
        batchWorker.processOne(accepted.batchId());
        assertThat(batchStore.summary(eventId,accepted.batchId()).counts()).containsEntry("BLOCKED",1L);
        assertThat(batchStore.items(eventId,accepted.batchId(),0,20,false).items().getFirst().errorCode()).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(cancelCount()).isZero();
        assertThat(amount("contract_amount")).isEqualByComparingTo("70000");
        verifyNoInteractions(toss);
    }

    /** 프로세스 종료처럼 소유권만 남은 경우 두 번째 실행기가 재획득하지 않는다. */
    @Test
    void claimedBatchCannotBeClaimedOrExecutedAgain() {
        Summary accepted=stageBatch();
        Work first=batchStore.claim(accepted.batchId());
        assertThat(first).isNotNull();
        assertThat(batchStore.claim(accepted.batchId())).isNull();
        batchWorker.processOne(accepted.batchId());
        assertThat(batchStore.summary(eventId,accepted.batchId()).status()).isEqualTo("RUNNING");
        verifyNoInteractions(toss);
        batchStore.finish(first,"NEEDS_REVIEW","TEST_INTERRUPTED",null);
    }
    /** DB 경계 검증에서만 실행 전 상태를 구성한다. 운영 진입점은 접수 직후 동기 실행한다. */
    private Summary stageBatch() {
        String requestId=id();
        AdminPaymentRefundRequest request=new AdminPaymentRefundRequest(requestId,"환불",List.of(registrationId),List.of());
        String batchId=batchStore.create(eventId,requestId,"fixture-admin","환불",Operation.FULL,
                "0".repeat(64),batchSelection.full(eventId,request));
        return batchStore.summary(eventId,batchId);
    }

    /** 한 요청이 RUNNING으로 남아도 다른 요청의 선점을 막지 않는다. */
    @Test void interruptedBatchDoesNotBlockOtherBatch() {
        Summary first=stageBatch();
        Summary second=stageBatch();
        Work firstWork=batchStore.claim(first.batchId());
        assertThat(firstWork).isNotNull();
        Work secondWork=batchStore.claim(second.batchId());
        assertThat(secondWork).isNotNull();
        batchStore.finish(secondWork,"BLOCKED","TEST_ONLY",null);
        batchStore.finish(firstWork,"NEEDS_REVIEW","TEST_INTERRUPTED",null);
    }

    /** 최초 응답을 받지 못해도 요청 ID로 조회할 수 있으며 조회는 PG를 호출하지 않는다. */
    @Test void requestIdLookupDoesNotExecutePendingRefund() {
        Summary accepted=stageBatch();
        Response response=batchStore.byRequest(eventId,accepted.requestId(),"fixture-admin");
        assertThat(response.summary().batchId()).isEqualTo(accepted.batchId());
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().status()).isEqualTo("PENDING");
        verifyNoInteractions(toss);
    }

    /** 토스 조회 실패도 증거를 저장하지만 금융 금액·상태·정원은 그대로 둔다. */
    @Test void evidencePersistsAndListsWithoutFinancialMutation() {
        AdminRefundPrepared prepared=service.prepareFull(eventId,null,List.of(registrationId),command());
        String cancelId=prepared.refunds().getFirst().paymentCancelId();
        String cancelStatus=jdbc.queryForObject("select status from payment_cancel where id=?",String.class,cancelId);
        BigDecimal contract=amount("contract_amount"),paid=amount("paid_amount");
        String registrationStatus=state();
        int totalCount=count(total),categoryCount=count(capacityA),shirtCount=count(shirt),cancels=cancelCount();
        when(evidenceClient.lookup(anyString())).thenAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new AdminRefundEvidenceModels.Lookup(401,"TOSS_LOOKUP_HTTP_ERROR",null);
        });
        AdminRefundEvidenceModels.Evidence result=evidenceService.check(eventId,cancelId,"fixture-admin");
        assertThat(result.verdict()).isEqualTo(AdminRefundEvidenceModels.Verdict.LOOKUP_UNAVAILABLE);
        assertThat(result.retryAllowed()).isFalse();
        assertThat(result.financialStateChanged()).isFalse();
        // JSON 왕복 시 금액의 scale은 바뀔 수 있으므로 수치로 비교하고 나머지 필드는 모두 검증한다.
        List<AdminRefundEvidenceModels.Evidence> savedEvidence = evidenceStore.list(eventId,cancelId,0,20).items();
        assertThat(savedEvidence).hasSize(1);
        assertThat(savedEvidence.getFirst())
                .usingRecursiveComparison()
                .ignoringAllOverriddenEquals()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(result);
        assertThat(amount("contract_amount")).isEqualByComparingTo(contract);
        assertThat(amount("paid_amount")).isEqualByComparingTo(paid);
        assertThat(state()).isEqualTo(registrationStatus);
        assertThat(count(total)).isEqualTo(totalCount);
        assertThat(count(capacityA)).isEqualTo(categoryCount);
        assertThat(count(shirt)).isEqualTo(shirtCount);
        assertThat(cancelCount()).isEqualTo(cancels);
        assertThat(jdbc.queryForObject("select status from payment_cancel where id=?",String.class,cancelId)).isEqualTo(cancelStatus);
        assertThatThrownBy(() -> evidenceStore.snapshot("other-event",cancelId)).isInstanceOf(kr.co.teambrain.marvelrun.admin.common.exception.CustomException.class);
        assertThatThrownBy(() -> evidenceStore.list(eventId,cancelId,0,101)).isInstanceOf(kr.co.teambrain.marvelrun.admin.common.exception.CustomException.class);
        verify(evidenceClient,times(1)).lookup(anyString());
        verifyNoInteractions(toss);
    }

    /** 정확히 100개 오류 대상은 접수·보존하고 101개는 기록/금융 실행 전에 거절한다. */
    @Test void batchTargetLimitAccepts100AndRejects101BeforeExecution() {
        List<String> missing=java.util.stream.IntStream.range(0,100).mapToObj(i -> id()).toList();
        Response accepted=batches.full(eventId,"fixture-admin",new AdminPaymentRefundRequest(id(),"상한 검증",missing,List.of()));
        assertThat(accepted.items()).hasSize(100);
        assertThat(accepted.summary().counts()).containsEntry("BLOCKED",100L);
        assertThat(accepted.resultsTruncated()).isFalse();
        List<String> over=new java.util.ArrayList<>(missing); over.add(id());
        String rejectedRequest=id();
        assertThatThrownBy(() -> batches.full(eventId,"fixture-admin",new AdminPaymentRefundRequest(rejectedRequest,"상한 검증",over,List.of())))
                .isInstanceOf(kr.co.teambrain.marvelrun.admin.common.exception.CustomException.class);
        assertThat(jdbc.queryForObject("select count(*) from admin_refund_batch where event_id=? and request_id=?",Integer.class,eventId,rejectedRequest)).isZero();
        assertThat(cancelCount()).isZero();
        verifyNoInteractions(toss);
    }

    /** 단체 1개라도 확장 인원이 상한을 넘으면 전부 거절하고 20행 경계에서 누락하지 않는다. */
    @ParameterizedTest
    @ValueSource(ints={21,101})
    void organizationCursorPagesAndExpandedLimit(int members) {
        String org=selectionOnlyOrganization(members);
        AdminPaymentRefundRequest request=new AdminPaymentRefundRequest(id(),"단체 조회 상한",List.of(),List.of(org));
        if(members==21) {
            List<Target> selected=batchSelection.full(eventId,request);
            assertThat(selected).hasSize(21);
            assertThat(selected.stream().map(Target::registrationId).distinct().count()).isEqualTo(21);
            assertThat(selected).allSatisfy(item -> assertThat(item.organizationId()).isEqualTo(org));
        } else {
            assertThatThrownBy(() -> batches.full(eventId,"fixture-admin",request))
                    .isInstanceOf(kr.co.teambrain.marvelrun.admin.common.exception.CustomException.class);
            assertThat(jdbc.queryForObject("select count(*) from admin_refund_batch where event_id=? and request_id=?",Integer.class,eventId,request.requestId())).isZero();
        }
        verifyNoInteractions(toss);
        assertThat(cancelCount()).isZero();
    }

    /** 실제 DB 동시 호출에서 동일 요청은 진행 상태만 반환하고 새 요청의 동일 대상은 차단한다. */
    @ParameterizedTest
    @ValueSource(booleans={true,false})
    void concurrentRequestCannotSendSecondRefund(boolean sameRequest) throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        AdminPaymentRefundRequest first=new AdminPaymentRefundRequest(id(),"동시 요청",List.of(registrationId),List.of());
        doAnswer(invocation -> {
            TossCancelAttempt attempt=invocation.getArgument(0);
            assertCommittedStart(attempt);
            entered.countDown();
            if(!release.await(20,java.util.concurrent.TimeUnit.SECONDS)) { throw new IllegalStateException("test release timeout"); }
            return verifiedOutcome(attempt);
        }).when(toss).cancel(any());
        try {
            java.util.concurrent.Future<Response> running=pool.submit(() -> batches.full(eventId,"fixture-admin",first));
            assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).as("first request reached mock PG").isTrue();
            AdminPaymentRefundRequest second=sameRequest ? first
                    : new AdminPaymentRefundRequest(id(),"동시 요청",List.of(registrationId),List.of());
            Response other=pool.submit(() -> batches.full(eventId,"fixture-admin",second))
                    .get(10,java.util.concurrent.TimeUnit.SECONDS);
            if(sameRequest) {
                assertThat(other.summary().counts()).containsEntry("RUNNING",1L);
            } else {
                assertThat(other.summary().counts()).containsEntry("BLOCKED",1L);
            }
            release.countDown();
            Response completed=running.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(completed.summary().counts()).containsEntry("SUCCEEDED",1L);
            assertThat(batchStore.byRequest(eventId,first.requestId(),"fixture-admin").summary().counts()).containsEntry("SUCCEEDED",1L);
            assertThat(cancelCount()).isEqualTo(1);
            assertThat(amount("paid_amount")).isEqualByComparingTo("0");
            verify(toss,times(1)).cancel(any());
        } finally {
            release.countDown();
            pool.shutdownNow();
            if(!pool.awaitTermination(15,java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트 작업이 종료되지 않았습니다. fixture 정리 전 DB 실행 상태 확인 필요");
            }
        }
    }

    /** 호출자가 최초 결과를 버려도 재조회/동일 요청 전송은 이미 완료된 환불을 재실행하지 않는다. */
    @Test void discardedResponseIsRecoveredByRequestIdWithoutResendingRefund() {
        AdminPaymentRefundRequest request=new AdminPaymentRefundRequest(id(),"응답 유실 모델",List.of(registrationId),List.of());
        doAnswer(invocation -> verifiedOutcome(invocation.getArgument(0))).when(toss).cancel(any());
        batches.full(eventId,"fixture-admin",request); // 반환값을 사용하지 않는다. 실제 TCP 단절은 EC2에서 별도 검증한다.
        Response recovered=batchStore.byRequest(eventId,request.requestId(),"fixture-admin");
        Response repeated=batches.full(eventId,"fixture-admin",request);
        assertThat(recovered.summary().counts()).containsEntry("SUCCEEDED",1L);
        assertThat(repeated.summary().batchId()).isEqualTo(recovered.summary().batchId());
        verify(toss,times(1)).cancel(any());
        assertThat(cancelCount()).isEqualTo(1);
    }

    /** 조회 분할만 검사할 단체 fixture다. 결제/예약을 만들지 않으며 PG 실행 대상으로 사용하지 않는다. */
    private String selectionOnlyOrganization(int members) {
        String org=id();
        tx.executeWithoutResult(status -> {
            jdbc.update("insert into organization(id,event_id,login_id,password,group_name,leader_name,leader_birth,leader_ph_num,guardian_consent,created_at) values(?,?,?,?,?,?,?,?,true,?)",
                    org,eventId,"g"+id().substring(0,15),"Test1234!","selection-only","leader","1990-01-01","010-0000-0000",now);
            for(int i=0;i<members;i++) {
                Registration member=Registration.builder().event(em.getReference(Event.class,eventId))
                        .organization(em.getReference(kr.co.teambrain.marvelrun.admin.user.command.application.domain.Organization.class,org))
                        .eventCategory(em.getReference(EventCategory.class,categoryA)).name("selection-"+i)
                        .phNum("010-0000-0000").birth("1990-01-01").gender(GenderClass.M).password("Test1234!")
                        .souvenirJson(List.of(new SouvenirJson(souvenir,"M"))).status(RegistrationStatus.PENDING)
                        .contractAmount(BigDecimal.ZERO).paidAmount(BigDecimal.ZERO)
                        .termsEssentialAgreed(true).termsMarketingAgreed(false).termsMarketingChannelAgreed(false)
                        .termsAgreedAt(now.minusDays(2)).build();
                em.persist(member);
            }
            em.flush();
        });
        return org;
    }

    /** READY/FAILED/INVALIDATED 주문과 귀속은 보존하고 최초 미결제만 한 번 반환한다. */
    @ParameterizedTest
    @ValueSource(strings = {"READY", "FAILED", "INVALIDATED"})
    void unpaidCancellationPreservesLedgerAndIsIdempotent(String paymentState) {
        prepareUnpaid(paymentState);
        var allocationBefore = jdbc.queryForList("select * from payment_allocation where payment_id=?", paymentId);
        var response = registrationCommands.deletePaymentPendingRegistration(registrationId);
        assertThat(response).isNotNull();
        assertThat(state()).isEqualTo("EXPIRED");
        assertThat(amount("contract_amount")).isEqualByComparingTo("0");
        assertThat(amount("paid_amount")).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select is_del from registration where id=?", Boolean.class, registrationId)).isTrue();
        assertThat(jdbc.queryForObject("select process_status from payment where id=?", String.class, paymentId))
                .isEqualTo(paymentState.equals("READY") ? "INVALIDATED" : paymentState);
        assertThat(jdbc.queryForList("select * from payment_allocation where payment_id=?", paymentId)).isEqualTo(allocationBefore);
        assertThat(jdbc.queryForObject("select amount from payment where id=?", BigDecimal.class, paymentId))
                .isEqualByComparingTo("70000");
        for (String capacity : List.of(total, capacityA, shirt)) {
            assertThat(held(capacity)).isZero(); assertThat(count(capacity)).isZero();
        }
        assertThat(jdbc.queryForObject("select status from reservation where id=?", String.class, reservationId)).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("select json_length(history) from reservation where id=?", Integer.class, reservationId)).isEqualTo(1);
        var once = unpaidSnapshot();
        registrationCommands.deletePaymentPendingRegistration(registrationId);
        assertThat(unpaidSnapshot()).isEqualTo(once);
        verifyNoInteractions(toss);
    }

    /** 참가 상태만 미결제여도 승인 중·결과 불명·대상 완료 결제가 있으면 거절한다. */
    @ParameterizedTest
    @ValueSource(strings = {"CONFIRMING", "UNKNOWN", "COMPLETED"})
    void unpaidCancellationRejectsUnsafePayment(String paymentState) {
        prepareUnpaid(paymentState);
        var before = unpaidSnapshot();
        assertThatThrownBy(() -> registrationCommands.deletePaymentPendingRegistration(registrationId))
                .isInstanceOfSatisfying(kr.co.teambrain.marvelrun.admin.common.exception.CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode.REGISTRATION_MODIFICATION_PAYMENT_CONFLICT));
        assertThat(unpaidSnapshot()).isEqualTo(before);
        verifyNoInteractions(toss);
    }

    /** 금액·예약 상태·예약 상세·금융 귀속의 불일치를 자동 보정하지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"paid", "PROCESSING", "CONSUMED", "missing-items", "missing-allocation", "missing-reservation"})
    void unpaidCancellationRejectsInconsistentState(String invalid) {
        prepareUnpaid("READY");
        tx.executeWithoutResult(status -> {
            switch (invalid) {
                case "paid" -> jdbc.update("update registration set paid_amount=1 where id=?", registrationId);
                case "PROCESSING", "CONSUMED" -> jdbc.update("update reservation set status=? where id=?", invalid, reservationId);
                case "missing-items" -> jdbc.update("delete from reservation_item where reservation_id=?", reservationId);
                case "missing-allocation" -> jdbc.update("delete from payment_allocation where payment_id=?", paymentId);
                case "missing-reservation" -> {
                    jdbc.update("delete from reservation_item where reservation_id=?", reservationId);
                    jdbc.update("delete from reservation where id=?", reservationId);
                }
                default -> throw new AssertionError(invalid);
            }
        });
        var before = unpaidSnapshot();
        assertThatThrownBy(() -> registrationCommands.deletePaymentPendingRegistration(registrationId))
                .isInstanceOf(kr.co.teambrain.marvelrun.admin.common.exception.CustomException.class);
        assertThat(unpaidSnapshot()).isEqualTo(before);
        verifyNoInteractions(toss);
    }

    /** 마지막 정원 반환이 실패하면 앞선 카운터·만료·예약 이력·주문 무효화까지 롤백한다. */
    @Test
    void unpaidCancellationRollsBackEarlierCapacityWrites() {
        prepareUnpaid("READY");
        String last = List.of(total, capacityA, shirt).stream().sorted().toList().getLast();
        jdbc.update("update capacity set held_count=0 where id=?", last);
        var before = unpaidSnapshot();
        assertThatThrownBy(() -> registrationCommands.deletePaymentPendingRegistration(registrationId))
                .isInstanceOfSatisfying(kr.co.teambrain.marvelrun.admin.common.exception.CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode.CAPACITY_COUNTER_MISMATCH));
        assertThat(unpaidSnapshot()).isEqualTo(before);
        verifyNoInteractions(toss);
    }

    /** 이미 반환된 미결제 예약의 신청 취소에서는 다른 참가자의 정원을 차감하지 않는다. */
    @Test
    void unpaidCancellationDoesNotReleaseAlreadyReleasedReservation() {
        prepareUnpaid("INVALIDATED");
        jdbc.update("update reservation set status='RELEASED' where id=?", reservationId);
        // 남아 있는 수량은 다른 신청의 확보분이라고 가정한다.
        registrationCommands.deletePaymentPendingRegistration(registrationId);
        for (String capacity : List.of(total, capacityA, shirt)) { assertThat(held(capacity)).isEqualTo(1); }
        assertThat(state()).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select json_length(history) from reservation where id=?", Integer.class, reservationId)).isZero();
    }

    /** 승인되지 않은 공동 주문은 보존·무효화하고 선택하지 않은 구성원 예약은 유지한다. */
    @Test
    void unpaidGroupCancellationPreservesOtherMemberAndSharedAllocation() {
        prepareUnpaid("READY");
        String otherId = unpaidGroupMember(false);
        var otherBefore = jdbc.queryForMap("select * from registration where id=?", otherId);
        var allocations = jdbc.queryForList("select * from payment_allocation where payment_id=? order by id", paymentId);
        registrationCommands.deletePaymentPendingRegistration(registrationId);
        assertThat(jdbc.queryForMap("select * from registration where id=?", otherId)).isEqualTo(otherBefore);
        assertThat(jdbc.queryForObject("select status from reservation where registration_id=?", String.class, otherId)).isEqualTo("HELD");
        assertThat(jdbc.queryForObject("select process_status from payment where id=?", String.class, paymentId)).isEqualTo("INVALIDATED");
        assertThat(jdbc.queryForList("select * from payment_allocation where payment_id=? order by id", paymentId)).isEqualTo(allocations);
        for (String capacity : List.of(total, capacityA, shirt)) { assertThat(held(capacity)).isEqualTo(1); }
        verifyNoInteractions(toss);
    }

    /** 같은 단체라도 다른 사람에게만 귀속된 완료 결제와 확정 정원을 변경하지 않는다. */
    @Test
    void unpaidGroupCancellationAllowsUnrelatedCompletedMember() {
        prepareUnpaid("READY");
        String otherId = unpaidGroupMember(true);
        var completedBefore = jdbc.queryForList("select * from payment where process_status='COMPLETED' and organization_id=(select organization_id from registration where id=?)", registrationId);
        registrationCommands.deletePaymentPendingRegistration(registrationId);
        assertThat(jdbc.queryForList("select * from payment where process_status='COMPLETED' and organization_id=(select organization_id from registration where id=?)", registrationId)).isEqualTo(completedBefore);
        assertThat(jdbc.queryForObject("select status from registration where id=?", String.class, otherId)).isEqualTo("CONFIRMED");
        for (String capacity : List.of(total, capacityA, shirt)) {
            assertThat(held(capacity)).isZero(); assertThat(count(capacity)).isEqualTo(1);
        }
        verifyNoInteractions(toss);
    }

    /** 환불 준비가 남은 범위는 참가 상태가 미결제로 표시되어도 취소를 차단한다. */
    @ParameterizedTest
    @ValueSource(strings = {"PROCESSING", "UNKNOWN"})
    void unpaidCancellationRejectsUnresolvedRefund(String cancellationState) {
        AdminRefundPrepared prepared = service.preparePartial(eventId, null, List.of(target(true)), command());
        prepareUnpaid("READY");
        jdbc.update("update payment_cancel set status=? where id=?", cancellationState, prepared.refunds().getFirst().paymentCancelId());
        var before = unpaidSnapshot();
        assertThatThrownBy(() -> registrationCommands.deletePaymentPendingRegistration(registrationId))
                .isInstanceOfSatisfying(kr.co.teambrain.marvelrun.admin.common.exception.CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode.PAYMENT_CANCEL_CONFLICT));
        assertThat(unpaidSnapshot()).isEqualTo(before);
        verifyNoInteractions(toss);
    }

    /** 실제 승인 API 호출 대신 동일한 DB 잠금·진행 상태를 재현하여 취소 경합을 검증한다. */
    @Test
    void unpaidCancellationCannotPassApprovalLocksOrConfirmingState() throws Exception {
        prepareUnpaid("READY");
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var approval = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForObject("select id from event where id=? for update", String.class, eventId);
                jdbc.queryForObject("select id from payment where id=? for update", String.class, paymentId);
                jdbc.update("update payment set process_status='CONFIRMING',version=version+1 where id=?", paymentId);
                jdbc.update("update reservation set status='PROCESSING',version=version+1 where id=?", reservationId);
                locked.countDown();
                awaitUnpaidTest(release);
            }));
            assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> registrationCommands.deletePaymentPendingRegistration(registrationId))
                    .isInstanceOfSatisfying(kr.co.teambrain.marvelrun.admin.common.exception.CustomException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode.CONCURRENT_MODIFICATION));
            release.countDown(); approval.get(10, java.util.concurrent.TimeUnit.SECONDS);
            var before = unpaidSnapshot();
            assertThatThrownBy(() -> registrationCommands.deletePaymentPendingRegistration(registrationId))
                    .isInstanceOfSatisfying(kr.co.teambrain.marvelrun.admin.common.exception.CustomException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode.REGISTRATION_MODIFICATION_PAYMENT_CONFLICT));
            assertThat(unpaidSnapshot()).isEqualTo(before);
            verifyNoInteractions(toss);
        } finally {
            release.countDown(); pool.shutdownNow();
            if (!pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) { throw new IllegalStateException("경합 테스트 종료 실패"); }
        }
    }

    /** 취소가 잠근 동안 중복 취소와 관리자 환불이 진입하지 않고 커밋 후 재호출은 무변경이다. */
    @Test
    void unpaidCancellationSerializesDuplicateAndRefundRequests() throws Exception {
        prepareUnpaid("READY");
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(() -> tx.executeWithoutResult(status -> {
                registrationCommands.deletePaymentPendingRegistration(registrationId);
                locked.countDown(); awaitUnpaidTest(release);
            }));
            assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            for (Runnable competing : List.<Runnable>of(
                    () -> registrationCommands.deletePaymentPendingRegistration(registrationId),
                    () -> service.prepareFull(eventId, null, List.of(registrationId), command()))) {
                assertThatThrownBy(competing::run)
                        .isInstanceOfSatisfying(kr.co.teambrain.marvelrun.admin.common.exception.CustomException.class,
                                e -> assertThat(e.getErrorCode()).isEqualTo(kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode.CONCURRENT_MODIFICATION));
            }
            release.countDown(); first.get(10, java.util.concurrent.TimeUnit.SECONDS);
            var once = unpaidSnapshot();
            registrationCommands.deletePaymentPendingRegistration(registrationId);
            assertThat(unpaidSnapshot()).isEqualTo(once);
            assertThat(held(total)).isZero();
            verifyNoInteractions(toss);
        } finally {
            release.countDown(); pool.shutdownNow();
            if (!pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) { throw new IllegalStateException("경합 테스트 종료 실패"); }
        }
    }

    /** UUID fixture를 최초 미결제 상황으로 전환한다. 외부 결제는 생성하지 않는다. */
    private void prepareUnpaid(String paymentState) {
        tx.executeWithoutResult(status -> {
            jdbc.update("update registration set status='PAYMENT_PENDING',paid_amount=0,contract_amount=70000,is_del=0 where id=?", registrationId);
            jdbc.update("update payment set process_status=?,toss_status=null,payment_key=null,approved_at=null where id=?", paymentState, paymentId);
            jdbc.update("update reservation set status='HELD',history=json_array() where id=?", reservationId);
            for (String capacity : List.of(total, capacityA, shirt)) {
                jdbc.update("update capacity set held_count=1,confirmed_count=0 where id=?", capacity);
            }
        });
    }

    /** 공동 미결제 주문 또는 별도 완료 주문을 가진 두 번째 단체원을 만든다. */
    private String unpaidGroupMember(boolean completed) {
        return tx.execute(status -> {
            String orgId = id();
            jdbc.update("insert into organization(id,event_id,login_id,password,group_name,leader_name,leader_birth,leader_ph_num,guardian_consent,created_at) values(?,?,?,?,?,?,?,?,true,?)",
                    orgId, eventId, "g" + id().substring(0, 15), "Test1234!", "fixture group", "leader", "1990-01-01", "010-0000-0000", now);
            jdbc.update("update registration set organization_id=? where id=?", orgId, registrationId);
            jdbc.update("update payment set registration_id=null,organization_id=?,amount=? where id=?", orgId, completed ? 70000 : 140000, paymentId);
            var org = em.getReference(kr.co.teambrain.marvelrun.admin.user.command.application.domain.Organization.class, orgId);
            Registration other = Registration.builder().organization(org).event(em.getReference(Event.class, eventId))
                    .eventCategory(em.getReference(EventCategory.class, categoryA)).name("other-" + id().substring(0, 8))
                    .phNum("010-0000-0000").birth("1990-01-01").gender(GenderClass.M).password("Test1234!")
                    .souvenirJson(List.of(new SouvenirJson(souvenir, "M")))
                    .status(completed ? RegistrationStatus.CONFIRMED : RegistrationStatus.PAYMENT_PENDING)
                    .contractAmount(new BigDecimal("70000")).paidAmount(completed ? new BigDecimal("70000") : BigDecimal.ZERO)
                    .termsEssentialAgreed(true).termsMarketingAgreed(false).termsMarketingChannelAgreed(false).termsAgreedAt(now).build();
            em.persist(other);
            Reservation reservation = Reservation.builder().registration(other)
                    .status(completed ? ReservationStatus.CONSUMED : ReservationStatus.HELD).build();
            em.persist(reservation);
            for (String capacity : List.of(total, capacityA, shirt)) {
                em.persist(ReservationItem.create(reservation, em.getReference(Capacity.class, capacity), 1));
                jdbc.update(completed ? "update capacity set confirmed_count=confirmed_count+1 where id=?"
                        : "update capacity set held_count=held_count+1 where id=?", capacity);
            }
            Payment payment = em.getReference(Payment.class, paymentId);
            if (completed) {
                payment = Payment.builder().organization(org).amount(new BigDecimal("70000"))
                        .orderId("test-" + id()).orderName("other paid member").purpose(PaymentPurpose.REGISTRATION_TRY)
                        .processStatus(PaymentProcessStatus.COMPLETED).tossStatus(TossPaymentStatus.DONE)
                        .paymentKey("fixture-" + id()).confirmIdempotencyKey(id()).approvedAt(now).build();
                em.persist(payment);
            }
            em.persist(PaymentAllocation.create(payment, other, new BigDecimal("70000")));
            em.flush(); return other.getId();
        });
    }

    /** 금융/신청/예약/카운터와 로그를 DB 현재값으로 비교한다. */
    private java.util.Map<String, Object> unpaidSnapshot() {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("registration", jdbc.queryForList("select * from registration where event_id=? order by id", eventId));
        result.put("payment", jdbc.queryForList("select * from payment where id=?", paymentId));
        result.put("allocation", jdbc.queryForList("select * from payment_allocation where payment_id=? order by id", paymentId));
        result.put("reservation", jdbc.queryForList("select * from reservation where registration_id=?", registrationId));
        result.put("items", jdbc.queryForList("select * from reservation_item where reservation_id=? order by id", reservationId));
        result.put("capacity", jdbc.queryForList("select * from capacity where event_id=? order by id", eventId));
        result.put("cancel", jdbc.queryForList("select * from payment_cancel where payment_id=? order by id", paymentId));
        result.put("log", jdbc.queryForList("select * from payment_process_log where payment_id=? order by id", paymentId));
        return result;
    }

    /** DB에 남은 임시 확보 수량을 조회한다. */
    private int held(String capacity) {
        return jdbc.queryForObject("select held_count from capacity where id=?", Integer.class, capacity);
    }

    /** 시간 제한을 둬 경합 테스트가 무한 대기하지 않도록 한다. */
    private static void awaitUnpaidTest(java.util.concurrent.CountDownLatch latch) {
        try {
            if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) { throw new IllegalStateException("경합 테스트 대기 초과"); }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(exception);
        }
    }

    /** 추가금은 주문/취소를 만들지 않고 응답·배치 증거로 전달하며 requestId 재조회는 실행하지 않는다. */
    /** 임시 차단後에는 BLOCKED 결과가 저장되고 동일 requestId 재조회는 상태를 바꾸지 않는다. */
    @Test
    void categoryPriceIncreaseBlockPersistsAndReplays() {
        jdbc.update("update event_category set amount=90000 where id=?",categoryB);
        String requestId = id();
        var request = new kr.co.teambrain.marvelrun.admin.payment.command.dto.AdminPaymentPartialRefundRequest(
                requestId,"성인 요금 정정",List.of(birthTarget()));
        var result = batches.partial(eventId,"fixture-admin",request);
        assertThat(result.items()).hasSize(1);
        var item = result.items().getFirst();
        assertThat(item.status()).isEqualTo("BLOCKED");
        assertThat(item.errorCode()).isEqualTo("ADMIN_ADJUSTMENT_CATEGORY_PRICE_INCREASE_TEMPORARILY_BLOCKED");
        assertThat(amount("contract_amount")).isEqualByComparingTo("70000");
        assertThat(amount("paid_amount")).isEqualByComparingTo("70000");
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(count(total)).isEqualTo(1);
        assertThat(count(capacityA)).isEqualTo(1); assertThat(count(capacityB)).isZero();
        assertThat(held(total)).isZero(); assertThat(held(capacityB)).isZero();
        assertThat(jdbc.queryForObject("select birth from registration where id=?",String.class,registrationId)).isEqualTo("1990-01-01");
        assertThat(jdbc.queryForObject("select status from reservation where id=?",String.class,reservationId)).isEqualTo("CONSUMED");
        assertThat(jdbc.queryForObject("select count(*) from payment where registration_id=?",Integer.class,registrationId)).isEqualTo(1);
        assertThat(cancelCount()).isZero();
        Long version = jdbc.queryForObject("select version from registration where id=?",Long.class,registrationId);
        assertThat(batches.partial(eventId,"fixture-admin",request).summary().batchId()).isEqualTo(result.summary().batchId());
        assertThat(jdbc.queryForObject("select version from registration where id=?",Long.class,registrationId)).isEqualTo(version);
        verifyNoInteractions(toss);
    }

    /** 정책 연령·보호자와 종목/기념품/정원 비활성은 관리자 변경을 막지 않는다. 실제 재고는 유지한다. */
    @Test
    void adjustmentBypassesParticipantPoliciesAndInactiveFlags() {
        jdbc.update("update event_category set amount=70000,is_active=false where id=?",categoryB);
        jdbc.update("update event_category_registration_policy set allowed_birth_to='1980-01-01' where event_category_id=?",categoryB);
        jdbc.update("update souvenir set is_active=false where id=?",souvenir);
        jdbc.update("update capacity set active=false where id=?",capacityB);
        var candidate = new AdminPaymentPartialRefundTarget(registrationId,categoryB,
                List.of(new SouvenirJson(souvenir,"M")),"1991-01-01",true);
        var prepared = service.preparePartial(eventId,null,List.of(candidate),command());
        assertThat(prepared.refunds()).isEmpty();
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("select birth from registration where id=?",String.class,registrationId)).isEqualTo("1991-01-01");
        assertThat(count(capacityB)).isEqualTo(1); assertThat(count(capacityA)).isZero();
        assertThat(held(capacityB)).isZero();
        verifyNoInteractions(toss);
    }

    /** 한도 부족은 항목 응답에 원인/수량을 노출하고 정보·계약·정원을 모두 보존한다. */
    @Test
    void adjustmentCapacityFailureExplainsAndRollsBack() {
        jdbc.update("update event_category set amount=70000 where id=?",categoryB);
        jdbc.update("update capacity set limit_count=0,active=false where id=?",capacityB);
        var result = batches.partial(eventId,"fixture-admin",
                new kr.co.teambrain.marvelrun.admin.payment.command.dto.AdminPaymentPartialRefundRequest(id(),"정원 부족",List.of(target(true))));
        assertThat(result.items().getFirst().status()).isEqualTo("BLOCKED");
        assertThat(result.items().getFirst().errorCode()).isEqualTo("CAPACITY_ACQUIRE_FAILED");
        assertThat(result.items().getFirst().message()).contains("한도");
        assertThat(amount("contract_amount")).isEqualByComparingTo("70000");
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(count(capacityA)).isEqualTo(1); assertThat(count(capacityB)).isZero();
        assertThat(cancelCount()).isZero(); verifyNoInteractions(toss);
    }

    /** 추가금 대기 중 다시 정정할 수 있으며 차액은 직전 계약이 아닌 실제 납부액 기준이다. */
    @Test
    void adjustmentCanReviseOutstandingAdditionalDue() {
        jdbc.update("update event_category set amount=70000 where id=?",categoryB);
        service.preparePartial(eventId,null,List.of(target(true)),command());
        // 임시 차단 도입 전에 이미 발생한 추가금 대기를 구성한다.
        jdbc.update("update registration set contract_amount=90000,status='ADDITIONAL_PAYMENT_REQUIRED' where id=?",registrationId);
        jdbc.update("update event_category set amount=80000 where id=?",categoryB);
        var prepared = service.preparePartial(eventId,null,List.of(target(true)),command());
        var member = prepared.members().getFirst();
        assertThat(member.contractAmountChange()).isEqualByComparingTo("-10000");
        assertThat(member.additionalPaymentAmount()).isEqualByComparingTo("10000");
        assertThat(prepared.refunds()).isEmpty();
        assertThat(state()).isEqualTo("ADDITIONAL_PAYMENT_REQUIRED");
        assertThat(count(capacityB)).isEqualTo(1);
        assertThat(cancelCount()).isZero(); verifyNoInteractions(toss);
    }

    /** 결제 시도가 없는 변경도 배치 증거 저장에 실패하면 함께 롤백되어 유실되지 않는다. */
    @Test
    void adjustmentEvidenceFailureRollsBackBusinessChange() {
        jdbc.update("update event_category set amount=70000 where id=?",categoryB);
        // 실패 설정만 실제 spy에 적용하고, 업무 실행은 기존 트랜잭션 프록시를 통한다.
        AdminRefundPreparationStore storeSpy = AopTestUtils.getUltimateTargetObject(store);
        assertThat(mockingDetails(storeSpy).isSpy()).isTrue();
        doThrow(new IllegalStateException("fixture evidence failure")).when(storeSpy).recordAdjustmentPrepared(any(),any());
        var result = batches.partial(eventId,"fixture-admin",
                new kr.co.teambrain.marvelrun.admin.payment.command.dto.AdminPaymentPartialRefundRequest(id(),"원자성",List.of(target(true))));
        assertThat(result.items().getFirst().status()).isEqualTo("NEEDS_REVIEW");
        assertThat(amount("contract_amount")).isEqualByComparingTo("70000");
        assertThat(state()).isEqualTo("CONFIRMED");
        assertThat(count(capacityA)).isEqualTo(1); assertThat(count(capacityB)).isZero();
        assertThat(cancelCount()).isZero(); verifyNoInteractions(toss);
    }

}
