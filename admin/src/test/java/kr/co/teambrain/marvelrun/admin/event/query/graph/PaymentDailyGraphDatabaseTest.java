package kr.co.teambrain.marvelrun.admin.event.query.graph;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.*;
import kr.co.teambrain.marvelrun.admin.event.query.dto.response.EventStatisticsResponse;
import kr.co.teambrain.marvelrun.admin.event.query.service.RegistrationQueryService;
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

/** 실제 MySQL 조회와 통계 서비스 집계를 검증하며 각 테스트의 데이터는 트랜잭션 롤백한다. */
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
@Import(PaymentDailyGraphRepository.class)
class PaymentDailyGraphDatabaseTest {
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentDailyGraphRepository repository;
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


    /** 단체 인원과 개인을 세고 추가결제 및 직접 귀속 중복을 제거하며 취소 후 과거 인원에서도 제외한다. */
    @Test
    void countsPeopleOnceAndRemovesCanceledHistory() {
        Registration first = registration(true, GenderClass.M, "1990-01-01");
        Registration second = registration(true, GenderClass.F, "1990-01-01");
        Registration personal = registration(false, GenderClass.M, "1990-01-01");
        payment(List.of(first,second),true,PaymentMethod.CARD,PaymentProcessStatus.COMPLETED,0);
        payment(List.of(first),true,PaymentMethod.CARD,PaymentProcessStatus.COMPLETED,1);
        payment(List.of(personal),false,PaymentMethod.CARD,PaymentProcessStatus.COMPLETED,0);
        jdbc.update("update registration set is_del=true,status='CANCELED',paid_amount=0 where id=?", first.getId());
        assertThat(counts(0)).containsExactly(new PaymentDailyGraphRepository.DailyCount(now.toLocalDate(),2));
    }

    /** 혼합 주문은 귀속별 목적을 적용하고 날짜 변환은 KST 자정 경계를 넘긴다. */
    @Test
    void mixedPurposeAndUtcMidnight() {
        Registration first = registration(true, GenderClass.M, "1990-01-01");
        Registration second = registration(true, GenderClass.F, "1990-01-01");
        payment(List.of(first,second),true,PaymentMethod.CARD,PaymentProcessStatus.COMPLETED,0);
        jdbc.update("update payment set purpose='MIXED_PAYMENT',approved_at=? where organization_id=?",
                now.toLocalDate().atTime(15,0), organizationId);
        jdbc.update("update payment_allocation set allocation_purpose='REGISTRATION_TRY' where registration_id=?", first.getId());
        jdbc.update("update payment_allocation set allocation_purpose='ADDITIONAL_PAYMENT' where registration_id=?", second.getId());
        assertThat(counts(540)).containsExactly(new PaymentDailyGraphRepository.DailyCount(now.toLocalDate().plusDays(1),1));
    }

    /** 개인 직접 귀속은 복구하지만 단체 귀속 누락 및 승인 시각 누락은 추정하지 않는다. */
    @Test
    void directFallbackAndMissingEvidence() {
        Registration personal = registration(false, GenderClass.M, "1990-01-01");
        Registration member = registration(true, GenderClass.F, "1990-01-01");
        payment(List.of(personal),false,PaymentMethod.CARD,PaymentProcessStatus.COMPLETED,0);
        payment(List.of(member),true,PaymentMethod.CARD,PaymentProcessStatus.COMPLETED,0);
        jdbc.update("delete from payment_allocation where registration_id in (?,?)",personal.getId(),member.getId());
        assertThat(counts(0)).containsExactly(new PaymentDailyGraphRepository.DailyCount(now.toLocalDate(),1));
        jdbc.update("update payment set approved_at=null where registration_id=?",personal.getId());
        assertThat(counts(0)).isEmpty();
    }

    /** 미완료 결제 및 0원 귀속은 신규 유료 결제자가 아니다. */
    @Test
    void excludesUnfinishedAndZeroAmount() {
        Registration first = registration(true, GenderClass.M, "1990-01-01");
        Registration second = registration(true, GenderClass.F, "1990-01-01");
        payment(List.of(first),true,PaymentMethod.CARD,PaymentProcessStatus.UNKNOWN,0);
        payment(List.of(second),true,PaymentMethod.CARD,PaymentProcessStatus.COMPLETED,0);
        jdbc.update("update payment_allocation set allocated_amount=0 where registration_id=?", second.getId());
        assertThat(counts(0)).isEmpty();
    }

    /** 접수 시작은 포함하고 종료 시각은 제외하며 다른 대회 유입은 섞지 않는다. */
    @Test
    void respectsHalfOpenBoundsAndEventScope() {
        Registration first = registration(false, GenderClass.M, "1990-01-01");
        payment(List.of(first),false,PaymentMethod.CARD,PaymentProcessStatus.COMPLETED,0);
        assertThat(repository.findDailyCounts(eventId,now,now.plusDays(1),0))
                .containsExactly(new PaymentDailyGraphRepository.DailyCount(now.toLocalDate(),1));
        assertThat(repository.findDailyCounts(eventId,now.minusDays(1),now,0)).isEmpty();
        assertThat(repository.findDailyCounts(id(),now,now.plusDays(1),0)).isEmpty();
    }

    /** 취소 접수는 잔액이 남아도 제외하고 추가결제·부분환불 대기는 포함한다. */
    @Test
    void excludesCancellationImmediatelyAndKeepsAdjustments() {
        Registration cancel = registration(true, GenderClass.M, "1990-01-01");
        Registration additional = registration(true, GenderClass.F, "1990-01-01");
        Registration refund = registration(true, GenderClass.M, "1990-01-01");
        payment(List.of(cancel, additional, refund),true,PaymentMethod.CARD,PaymentProcessStatus.COMPLETED,0);
        jdbc.update("update registration set status='CANCELLATION_PENDING' where id=?",cancel.getId());
        jdbc.update("update registration set status='ADDITIONAL_PAYMENT_REQUIRED',contract_amount=90000 where id=?",additional.getId());
        jdbc.update("update registration set status='PARTIAL_REFUND_REQUIRED',contract_amount=50000 where id=?",refund.getId());
        assertThat(counts(0)).containsExactly(new PaymentDailyGraphRepository.DailyCount(now.toLocalDate(),2));
        jdbc.update("update registration set status='CONFIRMED',paid_amount=50000 where id=?",refund.getId());
        assertThat(counts(0)).containsExactly(new PaymentDailyGraphRepository.DailyCount(now.toLocalDate(),2));
    }

    /** 전액환불·삭제·만료는 다른 필드가 남아 있어도 각각 독립적으로 제외한다. */
    @Test
    void excludesZeroBalanceDeletedAndExpired() {
        Registration zero = registration(true, GenderClass.M, "1990-01-01");
        Registration deleted = registration(true, GenderClass.F, "1990-01-01");
        Registration expired = registration(true, GenderClass.M, "1990-01-01");
        Registration canceled = registration(true, GenderClass.F, "1990-01-01");
        payment(List.of(zero,deleted,expired,canceled),true,PaymentMethod.CARD,PaymentProcessStatus.COMPLETED,0);
        jdbc.update("update registration set paid_amount=0 where id=?",zero.getId());
        jdbc.update("update registration set is_del=true where id=?",deleted.getId());
        jdbc.update("update registration set status='EXPIRED' where id=?",expired.getId());
        jdbc.update("update registration set status='CANCELED' where id=?",canceled.getId());
        assertThat(counts(0)).isEmpty();
    }

    /** 요청 대회와 기간의 실제 MySQL 집계를 실행한다. */
    private List<PaymentDailyGraphRepository.DailyCount> counts(int offset) {
        em.flush();
        return repository.findDailyCounts(eventId,now.minusDays(20),now.plusDays(3),offset);
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
