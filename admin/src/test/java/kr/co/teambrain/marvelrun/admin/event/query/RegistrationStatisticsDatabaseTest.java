package kr.co.teambrain.marvelrun.admin.event.query;

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
@Import(RegistrationQueryService.class)
class RegistrationStatisticsDatabaseTest {
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired RegistrationQueryService service;
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

    /** 단체 주문 한 건의 세 구성원을 모두 입금자로 세며 성별·연령·아동 표에도 반영한다. */
    @Test
    void groupMembersAppearInEveryStatisticsTable() {
        Registration first = registration(true,GenderClass.M,"1990-01-01");
        Registration second = registration(true,GenderClass.F,"1990-01-01");
        Registration child = registration(true,GenderClass.M,"2015-01-01");
        payment(List.of(first,second,child),true,PaymentMethod.CARD,PaymentProcessStatus.COMPLETED,0);
        var response = statistics();
        totals(response,3,3,3,0,0,3);
        assertThat(row(response.genderStats(),"입금자(남)").totalCount()).isEqualTo(2);
        assertThat(row(response.genderStats(),"입금자(여)").totalCount()).isEqualTo(1);
        assertThat(row(response.ageGroupStats(),"입금자(10대 이하)").totalCount()).isEqualTo(1);
        assertThat(row(response.childStats(),"입금자(아동)").totalCount()).isEqualTo(1);
        assertThat(row(response.childStats(),"입금자(일반)").totalCount()).isEqualTo(2);
    }

    /** 같은 단체라도 결제 귀속이 없는 구성원은 미결제로 남는다. */
    @Test
    void unpaidMemberOfPaidOrganizationIsNotCountedAsPaid() {
        Registration paid = registration(true,GenderClass.M,"1990-01-01");
        Registration unpaid = registration(true,GenderClass.F,"1990-01-01");
        em.flush();
        jdbc.update("update registration set paid_amount=0,status='PAYMENT_PENDING' where id=?",unpaid.getId());
        payment(List.of(paid),true,PaymentMethod.EASY_PAY,PaymentProcessStatus.COMPLETED,0);
        totals(statistics(),2,1,0,1,0,2);
    }

    /** 최초·추가 완료 결제가 있어도 한 명이며 최신 완료 결제수단을 사용한다. */
    @Test
    void multipleCompletedPaymentsDoNotDuplicateParticipant() {
        Registration member = registration(true,GenderClass.M,"1990-01-01");
        payment(List.of(member),true,PaymentMethod.CARD,PaymentProcessStatus.COMPLETED,0);
        payment(List.of(member),true,PaymentMethod.EASY_PAY,PaymentProcessStatus.COMPLETED,1);
        totals(statistics(),1,1,0,1,0,1);
    }

    /** 미완료 주문의 결제수단만으로 입금자로 분류하지 않는다. */
    @Test
    void unfinishedOrdersAreNotPaidParticipants() {
        for (PaymentProcessStatus status : List.of(PaymentProcessStatus.READY,PaymentProcessStatus.UNKNOWN,PaymentProcessStatus.FAILED)) {
            Registration member = registration(true,GenderClass.M,"1990-01-01");
            payment(List.of(member),true,PaymentMethod.CARD,status,0);
        }
        totals(statistics(),3,0,0,0,0,3);
    }

    /** 개인 직접 결제와 귀속 원장이 동시에 존재해도 중복되지 않는다. */
    @Test
    void personalAndGroupPaymentsRemainSeparated() {
        Registration personal = registration(false,GenderClass.M,"1990-01-01");
        Registration member = registration(true,GenderClass.F,"1990-01-01");
        payment(List.of(personal),false,PaymentMethod.CARD,PaymentProcessStatus.COMPLETED,0);
        payment(List.of(member),true,PaymentMethod.EASY_PAY,PaymentProcessStatus.COMPLETED,0);
        var response = statistics();
        totals(response,2,2,1,1,1,1);
        assertThat(row(response.genderStats(),"입금자(합계)").personalCount()).isEqualTo(1);
        assertThat(row(response.genderStats(),"입금자(합계)").groupCount()).isEqualTo(1);
    }

    /** 삭제된 단체원과 다른 대회 데이터는 해당 대회 집계에 포함하지 않는다. */
    @Test
    void deletedMemberAndOtherEventAreExcluded() {
        Registration member = registration(true,GenderClass.M,"1990-01-01");
        payment(List.of(member),true,PaymentMethod.CARD,PaymentProcessStatus.COMPLETED,0);
        em.flush();
        jdbc.update("update registration set is_del=true where id=?",member.getId());
        totals(statistics(),0,0,0,0,0,0);
        assertThat(service.getEventStatistics(id()).courseHeaders()).isEmpty();
    }

    /** 보고된 데이터처럼 납부액과 단체 주문은 있지만 귀속이 없는 구성원을 재현한다. */
    @Test
    void legacyPaidMembersWithoutAllocationsAppearInAllTables() {
        Registration first = registration(true,GenderClass.M,"1990-01-01");
        Registration second = registration(true,GenderClass.F,"2015-01-01");
        payment(List.of(first,second),true,PaymentMethod.EASY_PAY,PaymentProcessStatus.COMPLETED,0);
        removeAllocations(first,second);
        var response = statistics();
        totals(response,2,2,0,2,0,2);
        assertThat(row(response.genderStats(),"입금자(합계)").groupCount()).isEqualTo(2);
        assertThat(row(response.childStats(),"입금자(아동)").groupCount()).isEqualTo(1);
    }

    /** 기존 단체 주문이 있어도 미납자·무상 참가자를 유료 입금자로 포함하지 않는다. */
    @Test
    void legacyFallbackExcludesUnpaidAndFreeMembers() {
        Registration paid = registration(true,GenderClass.M,"1990-01-01");
        payment(List.of(paid),true,PaymentMethod.CARD,PaymentProcessStatus.COMPLETED,0);
        removeAllocations(paid);
        Registration unpaid = registration(true,GenderClass.F,"1990-01-01");
        Registration free = registration(true,GenderClass.M,"2022-01-01");
        em.flush();
        jdbc.update("update registration set paid_amount=0,status='PAYMENT_PENDING' where id=?",unpaid.getId());
        jdbc.update("update registration set paid_amount=0,contract_amount=0 where id=?",free.getId());
        totals(statistics(),3,1,1,0,0,3);
    }

    /** 귀속 없는 단체 완료 주문이 복수이면 구성원의 결제수단을 임의 선택하지 않는다. */
    @Test
    void legacyFallbackDoesNotGuessBetweenCompletedOrders() {
        Registration member = registration(true,GenderClass.M,"1990-01-01");
        payment(List.of(member),true,PaymentMethod.CARD,PaymentProcessStatus.COMPLETED,0);
        payment(List.of(member),true,PaymentMethod.EASY_PAY,PaymentProcessStatus.COMPLETED,1);
        removeAllocations(member);
        totals(statistics(),1,0,0,0,0,1);
    }

    /** 테스트 트랜잭션 안에서만 귀속 누락 상태를 재현하고 종료 시 함께 롤백한다. */
    private void removeAllocations(Registration... members) {
        em.flush();
        for (Registration member : members) {
            jdbc.update("delete from payment_allocation where registration_id=?",member.getId());
        }
        em.clear();
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
        jdbc.update("update payment set created_at=? where id=?",now.plusMinutes(sequence),payment.getId());
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

    /** 저장 내용을 DB로 반영한 뒤 실제 서비스와 실제 Repository를 실행한다. */
    private EventStatisticsResponse statistics() {
        em.flush(); em.clear();
        return service.getEventStatistics(eventId);
    }

    /** 세 통계 표에서 신청자·입금자·수단·종목별 합계를 동시에 검증한다. */
    private void totals(EventStatisticsResponse response,long applicants,long paid,long card,long easy,long personal,long group) {
        for (var rows : List.of(response.genderStats(),response.ageGroupStats(),response.childStats())) {
            var all = row(rows,"신청자(합계)");
            var deposited = row(rows,"입금자(합계)");
            assertThat(all.totalCount()).isEqualTo(applicants);
            assertThat(all.personalCount()).isEqualTo(personal);
            assertThat(all.groupCount()).isEqualTo(group);
            assertThat(all.unpaidCount()).isEqualTo(applicants-paid);
            assertThat(all.cardCount()).isEqualTo(card);
            assertThat(all.easyPayCount()).isEqualTo(easy);
            assertThat(deposited.totalCount()).isEqualTo(paid);
            assertThat(deposited.cardCount()).isEqualTo(card);
            assertThat(deposited.easyPayCount()).isEqualTo(easy);
            assertThat(deposited.unpaidCount()).isZero();
            assertThat(deposited.personalCount()+deposited.groupCount()).isEqualTo(paid);
            assertThat(all.courseCounts().getOrDefault("5km",0L)).isEqualTo(applicants);
            assertThat(deposited.courseCounts().getOrDefault("5km",0L)).isEqualTo(paid);
        }
    }

    /** 표시 행을 이름으로 찾아 응답 계약을 검증한다. */
    private EventStatisticsResponse.StatRowDto row(List<EventStatisticsResponse.StatRowDto> rows,String name) {
        return rows.stream().filter(value -> value.classification().equals(name)).findFirst().orElseThrow();
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
