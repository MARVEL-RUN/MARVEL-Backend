package kr.co.teambrain.marvelrun.admin.event.query.report;

import kr.co.teambrain.marvelrun.admin.event.query.graph.PaymentDailyGraphRepository;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.*;
import kr.co.teambrain.marvelrun.admin.payment.command.application.domain.PaymentAllocation;
import kr.co.teambrain.marvelrun.admin.user.command.application.domain.Organization;
import kr.co.teambrain.marvelrun.common.inheritance_enum.*;
import kr.co.teambrain.marvelrun.common.inheritance_enum.pg_payment.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

/** 실제 MySQL 조회로 엑셀 집계와 그래프 합계를 검증하며 각 테스트의 데이터는 트랜잭션 롤백한다. */
@Tag("admin-refund-db")
@EnabledIfEnvironmentVariable(named = "MARVELRUN_ADMIN_REFUND_DB_TEST", matches = "true")
@ActiveProfiles("admin-refund-test")
@DataJpaTest(showSql = false, properties = {
    "spring.datasource.url=${MARVELRUN_TEST_DB_URL}",
    "spring.datasource.username=${MARVELRUN_TEST_DB_USERNAME}",
    "spring.datasource.password=${MARVELRUN_TEST_DB_PASSWORD}",
    "spring.jpa.hibernate.ddl-auto=none", "spring.sql.init.mode=never",
    "spring.flyway.enabled=false", "spring.liquibase.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({RegistrationDailyReportRepository.class,PaymentDailyGraphRepository.class})
class RegistrationDailyReportDatabaseTest {
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired RegistrationDailyReportRepository repository;
    @Autowired PaymentDailyGraphRepository graph;
    private String eventId, categoryId, organizationId;
    private final LocalDateTime now = LocalDateTime.of(2026, 9, 23, 12, 0);

    /** 매 테스트마다 UUID 대회·종목·단체를 만들고 기존 운영 데이터는 사용하지 않는다. */
    @BeforeEach
    void fixture() {
        eventId = id(); categoryId = id(); organizationId = id();
        jdbc.update("""
            insert into event(id,name_kr,start_date,region,host,organizer,event_status,visible_status,
            regist_start_date,regist_deadline,payment_deadline,auto_max_regist,auto_start,auto_deadline,phone_auth_required)
            values(?,?,?,?,?,?,'CLOSED','OPEN',?,?,?,true,true,true,false)
            """, eventId,"statistics fixture",now.plusDays(10),"test","test","test",
            now.minusDays(20),now.minusDays(10),now.minusDays(5));
        jdbc.update("insert into event_category(id,event_id,amount,name,is_active,sort_order) values(?,?,70000,'5km',true,0)",categoryId,eventId);
        jdbc.update("""
            insert into organization(id,event_id,login_id,password,group_name,leader_name,leader_birth,
            leader_ph_num,guardian_consent,created_at) values(?,?,?,?,?,?,?,?,true,?)
            """,organizationId,eventId,testLoginId(),"fixture","fixture group","leader","1990-01-01","010-0000-0000",now);
    }


    /** 신청 다음 날 결제를 분리하며 아동 경계일과 단체 인원, 그래프 합계를 검증한다. */
    @Test
    void separatesApplicationAndPaymentDaysAndMatchesGraph() {
        Registration general = registration(true,GenderClass.M,"2013-10-31");
        Registration child = registration(true,GenderClass.F,"2013-11-01");
        payment(List.of(general,child),true,PaymentMethod.CARD,PaymentProcessStatus.COMPLETED,0);
        List<RegistrationDailyReportRepository.Aggregate> result = rows();
        assertThat(result).containsExactlyInAnyOrder(
                new RegistrationDailyReportRepository.Aggregate(now.toLocalDate().minusDays(1),categoryId,false,1,0),
                new RegistrationDailyReportRepository.Aggregate(now.toLocalDate().minusDays(1),categoryId,true,1,0),
                new RegistrationDailyReportRepository.Aggregate(now.toLocalDate(),categoryId,false,0,1),
                new RegistrationDailyReportRepository.Aggregate(now.toLocalDate(),categoryId,true,0,1));
        assertThat(graph.findDailyCounts(eventId,now.minusDays(20),now.plusDays(3),540))
                .containsExactly(new PaymentDailyGraphRepository.DailyCount(now.toLocalDate(),2));
    }

    /** 현재 취소 접수는 두 집계에서 제외하고 미결제는 신청자에만 포함하고 전액환불은 두 집계에서 제외한다. */
    @Test
    void currentFinancialStateChangesPastCounts() {
        Registration canceled = registration(true,GenderClass.M,"1990-01-01");
        Registration unpaid = registration(true,GenderClass.M,"1990-01-01");
        Registration partial = registration(true,GenderClass.M,"1990-01-01");
        Registration additional = registration(true,GenderClass.M,"1990-01-01");
        Registration zero = registration(true,GenderClass.M,"1990-01-01");
        payment(List.of(canceled,partial,additional,zero),true,PaymentMethod.CARD,PaymentProcessStatus.COMPLETED,0);
        jdbc.update("update registration set status='CANCELLATION_PENDING' where id=?",canceled.getId());
        jdbc.update("update registration set status='PAYMENT_PENDING',paid_amount=0 where id=?",unpaid.getId());
        jdbc.update("update registration set status='PARTIAL_REFUND_REQUIRED',contract_amount=50000 where id=?",partial.getId());
        jdbc.update("update registration set status='ADDITIONAL_PAYMENT_REQUIRED',contract_amount=90000 where id=?",additional.getId());
        jdbc.update("update registration set paid_amount=0 where id=?",zero.getId());
        assertThat(rows().stream().mapToLong(RegistrationDailyReportRepository.Aggregate::applicants).sum()).isEqualTo(3);
        assertThat(rows().stream().mapToLong(RegistrationDailyReportRepository.Aggregate::paid).sum()).isEqualTo(2);
        jdbc.update("update registration set status='CONFIRMED',paid_amount=50000 where id=?",partial.getId());
        assertThat(rows().stream().mapToLong(RegistrationDailyReportRepository.Aggregate::paid).sum()).isEqualTo(2);
    }

    /** UTC 15시 승인은 KST 다음 날 00시이며 종료 경계에서는 제외한다. */
    @Test
    void paymentMidnightAndRegistrationBoundsAreHalfOpen() {
        Registration member = registration(false,GenderClass.M,"1990-01-01");
        payment(List.of(member),false,PaymentMethod.CARD,PaymentProcessStatus.COMPLETED,0);
        jdbc.update("update payment set approved_at=? where registration_id=?",now.toLocalDate().atTime(15,0),member.getId());
        jdbc.update("update registration set registration_date=? where id=?",now,member.getId());
        List<RegistrationDailyReportRepository.Aggregate> before = repository.aggregate(eventId,now,
                now.toLocalDate().plusDays(1).atStartOfDay(),540);
        assertThat(before).containsExactly(new RegistrationDailyReportRepository.Aggregate(now.toLocalDate(),categoryId,false,1,0));
        List<RegistrationDailyReportRepository.Aggregate> after = repository.aggregate(eventId,now,
                now.toLocalDate().plusDays(2).atStartOfDay(),540);
        assertThat(after).contains(new RegistrationDailyReportRepository.Aggregate(now.toLocalDate().plusDays(1),categoryId,false,0,1));
        assertThat(repository.aggregate(id(),now,now.plusDays(3),540)).isEmpty();
    }

    /** 코스와 아동 구분 변경은 과거 당일 표와 누계의 기초 집계에도 반영한다. */
    @Test
    void currentCourseAndBirthAreUsedInsteadOfHistoricalValues() {
        Registration member = registration(false,GenderClass.M,"1990-01-01");
        payment(List.of(member),false,PaymentMethod.CARD,PaymentProcessStatus.COMPLETED,0);
        String nextCategory = id();
        jdbc.update("insert into event_category(id,event_id,amount,name,is_active,sort_order) values(?,?,70000,'2.3km',true,1)",nextCategory,eventId);
        jdbc.update("update registration set event_category_id=?,birth='2015-01-01' where id=?",nextCategory,member.getId());
        assertThat(rows()).containsExactlyInAnyOrder(
                new RegistrationDailyReportRepository.Aggregate(now.toLocalDate().minusDays(1),nextCategory,true,1,0),
                new RegistrationDailyReportRepository.Aggregate(now.toLocalDate(),nextCategory,true,0,1));
    }

    /** 귀속 없는 단체와 미완료 결제는 신청자로 남지만 결제일을 추정하지 않는다. */
    @Test
    void missingGroupAllocationAndUnknownAreNotGuessed() {
        Registration missing = registration(true,GenderClass.M,"1990-01-01");
        Registration unknown = registration(true,GenderClass.M,"1990-01-01");
        payment(List.of(missing),true,PaymentMethod.CARD,PaymentProcessStatus.COMPLETED,0);
        payment(List.of(unknown),true,PaymentMethod.CARD,PaymentProcessStatus.UNKNOWN,0);
        jdbc.update("delete from payment_allocation where registration_id=?",missing.getId());
        assertThat(rows().stream().mapToLong(RegistrationDailyReportRepository.Aggregate::applicants).sum()).isEqualTo(2);
        assertThat(rows().stream().mapToLong(RegistrationDailyReportRepository.Aggregate::paid).sum()).isZero();
    }

    /** UTC로 설정한 승인 시각을 KST로 바꿔 집계한다. */
    private List<RegistrationDailyReportRepository.Aggregate> rows() {
        em.flush();
        return repository.aggregate(eventId,now.minusDays(20),now.plusDays(3),540);
    }

    /** PG 호출 없이 실제 엔티티와 귀속 원장을 저장한다. */
    private void payment(List<Registration> members, boolean group, PaymentMethod method,
                         PaymentProcessStatus status, int sequence) {
        PaymentPurpose purpose = sequence == 0 ? PaymentPurpose.REGISTRATION_TRY : PaymentPurpose.ADDITIONAL_PAYMENT;
        Payment payment = Payment.builder()
            .registration(group ? null : members.getFirst())
            .organization(group ? em.getReference(Organization.class,organizationId) : null)
            .amount(new BigDecimal("70000").multiply(BigDecimal.valueOf(members.size())))
            .orderId("stats-"+id()).orderName("statistics fixture").purpose(purpose)
            .processStatus(status).paymentMethod(method).confirmIdempotencyKey(id()).build();
        em.persist(payment);
        for (Registration member : members) {
            em.persist(PaymentAllocation.create(payment,member,new BigDecimal("70000")));
        }
        em.flush();
        jdbc.update("update payment set created_at=?, approved_at=? where id=?",now.plusDays(sequence),now.plusDays(sequence),payment.getId());
    }

    /** 통계에 필요한 신청자를 생성한다. 정원 변경이나 외부 결제는 실행하지 않는다. */
    private Registration registration(boolean group, GenderClass gender, String birth) {
        Registration value = Registration.builder().event(em.getReference(Event.class,eventId))
            .eventCategory(em.getReference(EventCategory.class,categoryId))
            .organization(group ? em.getReference(Organization.class,organizationId) : null)
            .name("statistics-"+id()).phNum("010-0000-0000").birth(birth).gender(gender).password("fixture")
            .souvenirJson(List.of()).status(RegistrationStatus.CONFIRMED)
            .contractAmount(new BigDecimal("70000")).paidAmount(new BigDecimal("70000"))
            .termsEssentialAgreed(true).termsMarketingAgreed(false).termsMarketingChannelAgreed(false)
            .termsAgreedAt(now).build();
        em.persist(value);
        em.flush();
        jdbc.update("update registration set registration_date=? where id=?",now.minusDays(1),value.getId());
        return value;
    }

    /** login_id VARCHAR(30)에 맞춘 영문 접두사와 UUID 기반 28자리 테스트 계정이다. */
    private String testLoginId() {
        String value = "stat" + UUID.randomUUID().toString().replace("-", "").substring(0,24);
        assertThat(value).hasSize(28);
        return value;
    }

    /** 기존 데이터와 충돌하지 않는 식별자를 만든다. */
    private String id() { return UUID.randomUUID().toString(); }
}
