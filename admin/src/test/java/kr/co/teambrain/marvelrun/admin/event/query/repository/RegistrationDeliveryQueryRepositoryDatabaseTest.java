package kr.co.teambrain.marvelrun.admin.event.query.repository;

import jakarta.persistence.EntityManager;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.*;
import kr.co.teambrain.marvelrun.admin.user.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.admin.payment.command.application.domain.PaymentAllocation;
import kr.co.teambrain.marvelrun.admin.payment.command.application.domain.PaymentCancelAllocation;
import kr.co.teambrain.marvelrun.admin.event.query.dto.report.RegistrationDeliveryReportModels.*;
import kr.co.teambrain.marvelrun.admin.event.query.dto.PaymentDailyCountRow;
import kr.co.teambrain.marvelrun.admin.event.query.report.RegistrationDailyReportRow;
import kr.co.teambrain.marvelrun.common.inheritance_enum.*;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.*;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.pg_cancel.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** 명시적으로 활성화한 테스트 MySQL에서 배송·데일리의 귀속 및 승인일 SQL을 검증한다. PG는 호출하지 않는다. */
@Tag("delivery-db")
@EnabledIfEnvironmentVariable(named="MARVELRUN_DELIVERY_DB_TEST",matches="true")
@ActiveProfiles("delivery-test")
@DataJpaTest(showSql=false,properties={
        "spring.datasource.url=${MARVELRUN_TEST_DB_URL}","spring.datasource.username=${MARVELRUN_TEST_DB_USERNAME}",
        "spring.datasource.password=${MARVELRUN_TEST_DB_PASSWORD}","spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=never","spring.flyway.enabled=false","spring.liquibase.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=4"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({RegistrationDeliveryQueryRepository.class, RegistrationDailyReportQueryRepository.class})
class RegistrationDeliveryQueryRepositoryDatabaseTest {
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired RegistrationDeliveryQueryRepository repository;
    @Autowired RegistrationDailyReportQueryRepository dailyRepository;
    @Autowired PlatformTransactionManager manager;
    private String eventId;
    private String categoryId;
    private static final LocalDateTime START=LocalDateTime.of(2026,10,2,15,0);

    /** 각 테스트의 신청 자료는 기본 테스트 트랜잭션 종료 시 롤백한다. */
    @BeforeEach
    void createFixtureEvent() {
        eventId=id(); categoryId=id();
        insertEvent(eventId);
        jdbc.update("insert into event_category(id,event_id,amount,name,is_active,sort_order) values(?,?,40000,'A',true,0)",categoryId,eventId);
    }

    /** 재시도·추가결제·미승인·기간 경계·누락 승인일의 실제 SQL 결과를 검증한다. */
    @Test
    void selectsFirstCompletedInitialApprovalBeforeApplyingRange() {
        Registration inside=registration(null,RegistrationStatus.CONFIRMED,false);
        Payment failed=payment(inside,null,PaymentPurpose.REGISTRATION_TRY,PaymentProcessStatus.FAILED,START.minusDays(1),"40000");
        allocate(failed,inside,PaymentPurpose.REGISTRATION_TRY,"40000");
        Payment approved=payment(inside,null,PaymentPurpose.REGISTRATION_TRY,PaymentProcessStatus.COMPLETED,START,"40000");
        allocate(approved,inside,PaymentPurpose.REGISTRATION_TRY,"40000");
        Registration outside=registration(null,RegistrationStatus.CONFIRMED,false);
        allocate(payment(outside,null,PaymentPurpose.REGISTRATION_TRY,PaymentProcessStatus.COMPLETED,START.minusDays(1),"40000"),outside,PaymentPurpose.REGISTRATION_TRY,"40000");
        allocate(payment(outside,null,PaymentPurpose.ADDITIONAL_PAYMENT,PaymentProcessStatus.COMPLETED,START.plusHours(1),"10000"),outside,PaymentPurpose.ADDITIONAL_PAYMENT,"10000");
        Registration end=registration(null,RegistrationStatus.CONFIRMED,false);
        payment(end,null,PaymentPurpose.REGISTRATION_TRY,PaymentProcessStatus.COMPLETED,START.plusDays(1),"40000");
        Registration unknown=registration(null,RegistrationStatus.CONFIRMED,false);
        payment(unknown,null,PaymentPurpose.REGISTRATION_TRY,PaymentProcessStatus.COMPLETED,null,"40000");
        registration(null,RegistrationStatus.PAYMENT_PENDING,false);
        registration(null,RegistrationStatus.CANCELED,true);
        em.flush();

        List<Candidate> rows=repository.findDeliveryCandidates(eventId,START,START.plusDays(1),null,100);
        assertThat(rows).extracting(Candidate::id).containsExactly(inside.getId(),unknown.getId());
        assertThat(rows.getFirst().firstApprovedUtc()).isEqualTo(START);
        assertThat(repository.findPaymentFacts(List.of(inside.getId())).get(inside.getId())).hasSize(2);
    }

    /** 혼합 주문에서 신규 참가자만 최초 결제 귀속으로 판단하며 단체 주소를 사용한다. */
    @Test
    void mixedGroupPaymentKeepsParticipantSpecificPurposeAndRefund() {
        Organization organization=organization();
        Registration existing=registration(organization,RegistrationStatus.CONFIRMED,false);
        Registration added=registration(organization,RegistrationStatus.CONFIRMED,false);
        Payment initial=payment(null,organization,PaymentPurpose.REGISTRATION_TRY,PaymentProcessStatus.COMPLETED,START.minusDays(2),"40000");
        allocate(initial,existing,PaymentPurpose.REGISTRATION_TRY,"40000");
        Payment mixed=payment(null,organization,PaymentPurpose.MIXED_PAYMENT,PaymentProcessStatus.COMPLETED,START,"50000");
        PaymentAllocation existingAllocation=allocate(mixed,existing,PaymentPurpose.ADDITIONAL_PAYMENT,"10000");
        allocate(mixed,added,PaymentPurpose.REGISTRATION_TRY,"40000");
        PaymentCancel cancellation=PaymentCancel.builder().payment(mixed).cancelAmount(new BigDecimal("10000"))
                .cancelType(PaymentCancelType.PARTIAL).purpose(PaymentCancelPurpose.PRICE_ADJUSTMENT)
                .status(PaymentCancelStatus.UNKNOWN).cancelReason("테스트").idempotencyKey(id()).build();
        em.persist(cancellation);
        em.persist(PaymentCancelAllocation.create(cancellation,existingAllocation,new BigDecimal("10000")));
        em.flush();

        List<Candidate> rows=repository.findDeliveryCandidates(eventId,START,START.plusDays(1),null,100);
        assertThat(rows).extracting(Candidate::id).containsExactly(added.getId());
        assertThat(rows.getFirst().address()).isEqualTo("단체 배송지");
        Map<String,List<PaymentFact>> facts=repository.findPaymentFacts(List.of(existing.getId(),added.getId()));
        assertThat(facts.get(added.getId())).allMatch(p -> !p.refundUnsettled());
        assertThat(facts.get(existing.getId())).anyMatch(PaymentFact::refundUnsettled);
        Map<String,List<RefundFact>> refundHistory=repository.findUnclearRefundHistory(List.of(existing.getId(),added.getId()));
        assertThat(refundHistory).doesNotContainKey(added.getId());
        assertThat(refundHistory.get(existing.getId())).hasSize(1);
        assertThat(refundHistory.get(existing.getId()).getFirst().amount()).isEqualByComparingTo("10000");
        assertThat(refundHistory.get(existing.getId()).getFirst().status()).isEqualTo("UNKNOWN");

        // 같은 환불이 완료되어도 기존 참가자 귀속에만 반영한다.
        jdbc.update("update payment_cancel set status='DONE' where id=?",cancellation.getId());
        Map<String,List<PaymentFact>> completed=repository.findPaymentFacts(List.of(existing.getId(),added.getId()));
        assertThat(completed.get(added.getId())).allMatch(p -> p.refundedAmount().signum()==0);
        assertThat(completed.get(existing.getId())).anyMatch(p -> p.refundedAmount().compareTo(new BigDecimal("10000"))==0);
    }

    /** 배분 없는 단체 환불은 금액 미확인으로, 직접 개인 환불은 해당 개인 금액으로 조회한다. */
    @Test
    void refundHistoryDistinguishesMissingGroupAttributionAndDirectPersonalRefund() {
        Organization group=organization();
        Registration member=registration(group,RegistrationStatus.CONFIRMED,false);
        Payment groupPayment=payment(null,group,PaymentPurpose.REGISTRATION_TRY,PaymentProcessStatus.COMPLETED,START,"80000");
        allocate(groupPayment,member,PaymentPurpose.REGISTRATION_TRY,"40000");
        PaymentCancel groupCancel=PaymentCancel.builder().payment(groupPayment).cancelAmount(new BigDecimal("80000"))
                .cancelType(PaymentCancelType.FULL).purpose(PaymentCancelPurpose.REGISTRATION_CANCELLATION)
                .status(PaymentCancelStatus.UNKNOWN).cancelReason("테스트").idempotencyKey(id()).build();
        em.persist(groupCancel);
        Registration personal=registration(null,RegistrationStatus.CONFIRMED,false);
        Payment personalPayment=payment(personal,null,PaymentPurpose.REGISTRATION_TRY,PaymentProcessStatus.COMPLETED,START,"40000");
        PaymentCancel personalCancel=PaymentCancel.builder().payment(personalPayment).cancelAmount(new BigDecimal("10000"))
                .cancelType(PaymentCancelType.PARTIAL).purpose(PaymentCancelPurpose.PRICE_ADJUSTMENT)
                .status(PaymentCancelStatus.DONE).cancelReason("테스트").idempotencyKey(id()).build();
        em.persist(personalCancel);
        em.flush();

        // KST 저장값을 조회 단계에서 재보정하지 않는다.
        jdbc.update("update payment_cancel set requested_at=?,canceled_at=? where id=?",
                START.plusHours(9),START.plusHours(10),personalCancel.getId());
        Map<String,List<RefundFact>> history=repository.findUnclearRefundHistory(List.of(member.getId(),personal.getId()));
        assertThat(history.get(member.getId())).hasSize(1);
        assertThat(history.get(member.getId()).getFirst().amount()).isNull();
        RefundFact direct=history.get(personal.getId()).getFirst();
        assertThat(direct.amount()).isEqualByComparingTo("10000");
        assertThat(direct.requestedKst()).isEqualTo(START.plusHours(9));
        assertThat(direct.canceledKst()).isEqualTo(START.plusHours(10));
        assertThat(repository.findUnclearRefundHistory(List.of())).isEmpty();
    }

    /** 현재 상세 조회와 history 선택 조회 및 대회 범위 자원 매핑 SQL을 검증한다. */
    @Test
    void loadsHistoryOnlyOnDemandAndScopesCapacityMappingsToEvent() {
        Registration row=registration(null,RegistrationStatus.CONFIRMED,false); em.flush();
        String capacity=id(); String reservation=id();
        jdbc.update("""
            insert into capacity(id,event_id,type,resource_key,name,size,limit_count,held_count,confirmed_count,active,created_at,updated_at)
            values(?,?,'CATEGORY',?,'A','',10,0,1,true,?,?)
            """,capacity,eventId,capacity,START,START);
        jdbc.update("insert into capacity_category(id,capacity_id,event_category_id) values(?,?,?)",id(),capacity,categoryId);
        jdbc.update("""
            insert into reservation(id,registration_id,status,version,created_at,updated_at,hold_sequence,history)
            values(?,?,'CONSUMED',0,?,?,1,'[]')
            """,reservation,row.getId(),START,START);
        jdbc.update("insert into reservation_item(id,reservation_id,capacity_id,quantity) values(?,?,?,1)",id(),reservation,capacity);

        ReservationFact current=repository.findReservations(List.of(row.getId()),false).get(row.getId()).getFirst();
        assertThat(current.history()).isNull();
        assertThat(current.items()).containsExactly(new CapacityItem(capacity,1));
        assertThat(repository.findReservations(List.of(row.getId()),true).get(row.getId()).getFirst().history()).isEqualTo("[]");
        assertThat(repository.findCapacities(eventId,List.of(capacity)).get(capacity).categoryNames()).containsExactly("A");
        assertThat(repository.findCapacities(id(),List.of(capacity))).isEmpty();
    }

    /** 외부결제의 PG 전용 값이 비어 있어도 동일한 최초 승인일 조건에 포함한다. */
    @Test
    void includesOfflineApprovalWithKstRegistrationDate() {
        Registration registration=registration(null,RegistrationStatus.CONFIRMED,false);
        Payment offline=Payment.createOfflineCompletedPayment(registration,START);
        em.persist(offline); allocate(offline,registration,PaymentPurpose.REGISTRATION_TRY,"40000"); em.flush();
        jdbc.update("update registration set external_payment=true,registration_date=? where id=?",START.plusHours(9),registration.getId());
        Candidate result=repository.findDeliveryCandidates(eventId,START,START.plusDays(1),null,100).getFirst();
        assertThat(result.firstApprovedUtc()).isEqualTo(START);
        assertThat(result.registrationAt()).isEqualTo(START.plusHours(9));
        assertThat(repository.findPaymentFacts(List.of(registration.getId())).get(registration.getId()).getFirst().tossStatus()).isNull();
    }

    /** 같은 승인 시각 및 NULL 승인일 사이에서 페이지가 나뉘어도 중복·누락하지 않는다. */
    @Test
    void keysetPaginationIncludesTiedAndUnknownApprovalsOnce() {
        Set<String> expected=new HashSet<>();
        for (int i=0;i<7;i++) {
            Registration row=registration(null,RegistrationStatus.CONFIRMED,false); expected.add(row.getId());
            payment(row,null,PaymentPurpose.REGISTRATION_TRY,PaymentProcessStatus.COMPLETED,i<4 ? START : null,"40000");
        }
        em.flush();
        List<String> actual=new ArrayList<>(); Candidate cursor=null;
        for (int page=0;page<5;page++) {
            List<Candidate> batch=repository.findDeliveryCandidates(eventId,START,START.plusDays(1),cursor,2);
            if (batch.isEmpty()) { break; }
            actual.addAll(batch.stream().map(Candidate::id).toList()); cursor=batch.getLast();
        }
        assertThat(actual).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
    }

    /** 다른 연결의 커밋이 두 단계 사이에 발생해도 반복 읽기 스냅샷을 유지한다. */
    @Test
    void repeatableReadRetainsSnapshotAcrossCommittedChange() {
        String isolated=id();
        TransactionTemplate independent=new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.executeWithoutResult(status -> insertEvent(isolated));
        try {
            TransactionTemplate reader=new TransactionTemplate(manager);
            reader.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            reader.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            reader.setReadOnly(true);
            reader.executeWithoutResult(status -> {
                String before=repository.findDeliveryEvent(isolated).orElseThrow().name();
                independent.executeWithoutResult(write -> jdbc.update("update event set name_kr='변경된 대회' where id=?",isolated));
                assertThat(repository.findDeliveryEvent(isolated).orElseThrow().name()).isEqualTo(before);
            });
            independent.executeWithoutResult(status -> assertThat(repository.findDeliveryEvent(isolated).orElseThrow().name()).isEqualTo("변경된 대회"));
        } finally {
            independent.executeWithoutResult(status -> jdbc.update("delete from event where id=?",isolated));
        }
    }

    /** 개인·단체·외부·레거시의 승인일 변환과 그래프의 시작 포함·종료 제외를 실제 SQL로 검증한다. */
    @Test
    void dailyReportAndGraphUseKstApprovalsInsteadOfOrderCreation() {
        // 서로 다른 결제 귀속과 KST 자정 전후의 승인 원장을 구성한다.
        LocalDateTime startKst = START.plusHours(9);
        LocalDateTime endKst = startKst.plusDays(1);
        Registration personal = registration(null, RegistrationStatus.CONFIRMED, false);
        Payment first = payment(personal, null, PaymentPurpose.REGISTRATION_TRY,
                PaymentProcessStatus.COMPLETED, START.minusSeconds(1), "40000");
        allocate(first, personal, PaymentPurpose.REGISTRATION_TRY, "40000");
        payment(personal, null, PaymentPurpose.ADDITIONAL_PAYMENT,
                PaymentProcessStatus.COMPLETED, START.plusHours(2), "10000");
        payment(personal, null, PaymentPurpose.REGISTRATION_TRY,
                PaymentProcessStatus.READY, START.minusDays(1), "40000");
        Organization group = organization();
        Registration member = registration(group, RegistrationStatus.CONFIRMED, false);
        Payment grouped = payment(null, group, PaymentPurpose.REGISTRATION_TRY,
                PaymentProcessStatus.COMPLETED, START, "40000");
        allocate(grouped, member, PaymentPurpose.REGISTRATION_TRY, "40000");
        Registration offline = registration(null, RegistrationStatus.CONFIRMED, false);
        payment(offline, null, PaymentPurpose.REGISTRATION_TRY,
                PaymentProcessStatus.COMPLETED, START, "40000");
        Organization legacyGroup = organization();
        registration(legacyGroup, RegistrationStatus.CONFIRMED, false);
        payment(null, legacyGroup, PaymentPurpose.REGISTRATION_TRY,
                PaymentProcessStatus.COMPLETED, START.plusHours(1), "40000");
        Registration atEnd = registration(null, RegistrationStatus.CONFIRMED, false);
        payment(atEnd, null, PaymentPurpose.REGISTRATION_TRY,
                PaymentProcessStatus.COMPLETED, START.plusDays(1), "40000");
        Registration unknown = registration(null, RegistrationStatus.CONFIRMED, false);
        payment(unknown, null, PaymentPurpose.REGISTRATION_TRY,
                PaymentProcessStatus.COMPLETED, null, "40000");
        em.flush();

        // 주문일은 승인일과 다르게 고정하고 외부결제는 승인일 보완으로만 조회되게 한다.
        jdbc.update("update registration set registration_date=? where event_id=?", startKst, eventId);
        jdbc.update("update registration set external_payment=true,registration_date=? where id=?",
                startKst.minusDays(20), offline.getId());
        jdbc.update("update payment set created_at=? where registration_id in (select id from registration where event_id=?) or organization_id in (select id from organization where event_id=?)",
                startKst.minusDays(2), eventId, eventId);

        // 엑셀 원본은 승인일 NULL을 보존하고 직접·Allocation 중복은 신청 1행으로 합친다.
        List<RegistrationDailyReportRow> rows = dailyRepository.findReportRows(eventId, startKst, endKst);
        assertThat(rows).hasSize(6);
        assertThat(rows).extracting(RegistrationDailyReportRow::firstPaidAt)
                .containsExactlyInAnyOrder(startKst.minusSeconds(1), startKst, startKst,
                        startKst.plusHours(1), endKst, null);
        assertThat(dailyRepository.findPaymentDailyCounts(eventId, startKst, endKst))
                .containsExactly(new PaymentDailyCountRow(startKst.toLocalDate(), 3L));

        // 배송과 데일리에 모두 귀속이 있는 단체원은 같은 KST 승인일을 사용한다.
        Candidate delivery = repository.findDeliveryCandidates(eventId, START, START.plusDays(1), null, 100)
                .stream().filter(row -> row.id().equals(member.getId())).findFirst().orElseThrow();
        assertThat(delivery.firstApprovedUtc().plusHours(9)).isEqualTo(startKst);
    }

    /** 외부결제의 신청일이 범위 밖이면 UTC로 환산한 승인 경계에서만 보완 포함한다. */
    @Test
    void externalDailyFallbackUsesUtcBoundariesForKstRange() {
        // 세 신청 모두 신청일 필터를 통과하지 않도록 설정한다.
        LocalDateTime startKst = START.plusHours(9);
        for (LocalDateTime approval : List.of(START.minusSeconds(1), START, START.plusDays(1))) {
            Registration row = registration(null, RegistrationStatus.CONFIRMED, false);
            payment(row, null, PaymentPurpose.REGISTRATION_TRY,
                    PaymentProcessStatus.COMPLETED, approval, "40000");
        }
        em.flush();
        jdbc.update("update registration set external_payment=true,registration_date=? where event_id=?",
                startKst.minusDays(20), eventId);

        // 시작과 정확히 같은 승인만 남고 직전·종료와 같은 승인은 제외된다.
        assertThat(dailyRepository.findReportRows(eventId, startKst, startKst.plusDays(1)))
                .extracting(RegistrationDailyReportRow::firstPaidAt).containsExactly(startKst);
        assertThat(dailyRepository.findPaymentDailyCounts(eventId, startKst, startKst.plusDays(1)))
                .containsExactly(new PaymentDailyCountRow(startKst.toLocalDate(), 1L));
    }

    /** 실제 스키마의 필수 대회 값만 테스트 전용 식별자로 생성한다. */
    private void insertEvent(String event) {
        jdbc.update("""
            insert into event(id,name_kr,start_date,region,host,organizer,event_status,visible_status,
                regist_start_date,regist_deadline,payment_deadline,auto_max_regist,auto_start,auto_deadline,phone_auth_required)
            values(?, '배송 테스트', ?, 'test','test','test','CLOSED','OPEN',?,?,?,true,true,true,false)
            """,event,START.plusMonths(1),START.minusDays(10),START.plusDays(10),START.plusDays(10));
    }

    /** 테스트 참가자를 생성하며 실제 개인정보와 외부 서비스 식별자는 사용하지 않는다. */
    private Registration registration(Organization organization,RegistrationStatus state,boolean deleted) {
        Registration row=Registration.builder().event(em.getReference(Event.class,eventId)).eventCategory(em.getReference(EventCategory.class,categoryId))
                .organization(organization).name("test-"+id().substring(0,8)).phNum("010-0000-0000").birth("1990-01-01")
                .gender(GenderClass.M).password("fixture").address("개인 주소").addressDetail("상세").souvenirJson(List.of())
                .status(state).softDeleted(deleted).contractAmount(new BigDecimal("40000")).paidAmount(new BigDecimal("40000"))
                .termsEssentialAgreed(true).termsMarketingAgreed(false).termsMarketingChannelAgreed(false).termsAgreedAt(START).build();
        em.persist(row); return row;
    }

    /** 관리자 단체 엔티티는 빌더가 없어 테스트 SQL로 필수 정보를 생성한다. */
    private Organization organization() {
        String organization=id();
        jdbc.update("""
            insert into organization(id,event_id,login_id,password,group_name,leader_name,leader_birth,leader_ph_num,
                guardian_consent,created_at,address,address_detail)
            values(?,?,?,'fixture','단체','대표','1990-01-01','01000000000',false,?,'단체 배송지','단체 상세')
            """,organization,eventId,organization.substring(0,20),START);
        return em.getReference(Organization.class,organization);
    }

    /** 승인 결과는 테스트 원장에만 구성하며 외부 결제 호출을 하지 않는다. */
    private Payment payment(Registration row,Organization organization,PaymentPurpose purpose,PaymentProcessStatus state,LocalDateTime approved,String amount) {
        Payment payment=Payment.builder().registration(row).organization(organization).amount(new BigDecimal(amount))
                .orderId(id()).orderName("fixture").purpose(purpose).processStatus(state).approvedAt(approved).confirmIdempotencyKey(id()).build();
        em.persist(payment); return payment;
    }

    /** 혼합 주문에서도 참가자별 최초/추가 목적을 보존한다. */
    private PaymentAllocation allocate(Payment payment,Registration registration,PaymentPurpose purpose,String amount) {
        PaymentAllocation allocation=PaymentAllocation.create(payment,registration,new BigDecimal(amount),purpose);
        em.persist(allocation); return allocation;
    }

    /** 다른 테스트 및 기존 자료와 충돌하지 않는 식별자다. */
    private String id() { return UUID.randomUUID().toString(); }
}
