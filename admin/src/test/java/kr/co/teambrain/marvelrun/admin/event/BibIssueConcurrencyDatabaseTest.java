package kr.co.teambrain.marvelrun.admin.event;

import jakarta.persistence.EntityManager;
import kr.co.teambrain.marvelrun.admin.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.BibIssueRequest;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.BibIssueResponse;
import kr.co.teambrain.marvelrun.admin.event.command.application.service.BibIssueService;
import kr.co.teambrain.marvelrun.admin.event.command.repository.BibIssueRepository;
import kr.co.teambrain.marvelrun.admin.event.query.support.RegistrationActionPolicyReader;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 같은 차수의 배번 발급을 두 요청이 동시에 보내도 번호가 겹치지 않는지 실제 DB에서 확인한다.
 * 각 발급이 자기 트랜잭션을 쓰도록 테스트 트랜잭션을 끄고, 만든 데이터는 끝난 뒤 직접 지운다.
 */
@EnabledIfEnvironmentVariable(named = "MARVELRUN_BIB_ISSUE_DB_TEST", matches = "true")
@ActiveProfiles("admin-refund-test")
@DataJpaTest(showSql = false, properties = {
        "spring.datasource.url=${MARVELRUN_TEST_DB_URL}",
        "spring.datasource.username=${MARVELRUN_TEST_DB_USERNAME}",
        "spring.datasource.password=${MARVELRUN_TEST_DB_PASSWORD}",
        "spring.jpa.hibernate.ddl-auto=none", "spring.sql.init.mode=never",
        "spring.flyway.enabled=false", "spring.liquibase.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({BibIssueService.class, BibIssueRepository.class, RegistrationActionPolicyReader.class})
class BibIssueConcurrencyDatabaseTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 1, 0, 0);
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 11, 20, 17, 0);
    private static final int REGISTRATIONS = 5;

    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired BibIssueService service;
    @MockitoBean ServerTimeProvider time;

    private TransactionTemplate tx;
    private String eventId;
    private String categoryId;

    /** 버추얼 대회, 단품 종목 하나, 마감된 1차수, 홈페이지 순번, 확정 신청 다섯 건을 커밋해 둔다. */
    @BeforeEach
    void fixture() {
        when(time.currentDateTime()).thenReturn(DEADLINE.plusHours(1));
        tx = new TransactionTemplate(manager);
        eventId = UUID.randomUUID().toString();
        categoryId = UUID.randomUUID().toString();
        tx.executeWithoutResult(status -> {
            jdbc.update("""
                    insert into event(id,name_kr,start_date,region,host,organizer,event_status,visible_status,
                        regist_start_date,regist_deadline,payment_deadline,auto_max_regist,auto_start,auto_deadline,phone_auth_required,event_format)
                    values(?, '배번 동시 발급 테스트', ?, 'test','test','test','OPEN','OPEN',?,?,NULL,true,true,true,false,'VIRTUAL')
                    """, eventId, DEADLINE.plusDays(7), START, LocalDateTime.of(2027, 1, 19, 23, 59, 59));
            jdbc.update("insert into event_category(id,event_id,amount,name,is_active,sort_order,distance_meters,product_type,parent_category_id) values(?,?,40000,'A',true,1,5000,'SINGLE',NULL)",
                    categoryId, eventId);
            jdbc.update("INSERT INTO event_category_start_group (id,event_category_id,code,description,sort_order,min_pace_seconds_per_km,min_expected_finish_seconds,is_active) VALUES (UUID(),?,'ALL','버추얼런',10,0,0,1)",
                    categoryId);
            for (String action : List.of("MODIFY", "PAYMENT", "REFUND")) {
                jdbc.update("insert into registration_action_policy(id,event_id,action_type,registration_start_at,registration_end_at,effective_from,enabled) values(?,?,?,?,?,?,1)",
                        UUID.randomUUID().toString(), eventId, action, START, DEADLINE, DEADLINE);
            }
            jdbc.update("insert into bib_sequence(event_id,event_category_id,channel,start_number,last_number) values(?,?,'HOMEPAGE',10001,NULL)",
                    eventId, categoryId);
            String startGroupId = jdbc.queryForObject("select id from event_category_start_group where event_category_id=?", String.class, categoryId);
            for (int i = 0; i < REGISTRATIONS; i++) {
                Registration row = Registration.builder()
                        .startGroupId(startGroupId).startGroupUnselected(true)
                        .event(em.getReference(Event.class, eventId)).eventCategory(em.getReference(EventCategory.class, categoryId))
                        .name("동시-" + i).phNum("010-0000-0000").birth("1990-01-01")
                        .gender(GenderClass.M).password("fixture").address("주소").addressDetail("상세").souvenirJson(List.of())
                        .status(RegistrationStatus.CONFIRMED).softDeleted(false).externalPayment(false)
                        .contractAmount(new BigDecimal("40000")).paidAmount(new BigDecimal("40000"))
                        .termsEssentialAgreed(true).termsMarketingAgreed(false).termsMarketingChannelAgreed(false).termsAgreedAt(START).build();
                em.persist(row);
                em.flush();
                jdbc.update("update registration set registration_date=? where id=?", DEADLINE.minusHours(REGISTRATIONS - i), row.getId());
                String itemId = UUID.randomUUID().toString();
                jdbc.update("""
                        insert into registration_item(id,event_id,registration_id,event_category_id,sort_order,amount,souvenir_json)
                        values(?,?,?,?,1,40000,'[]')
                        """, itemId, eventId, row.getId(), categoryId);
                jdbc.update("""
                        insert into registration_entry(id,event_id,registration_id,registration_item_id,event_category_id,name,ph_num,birth,gender,created_at,updated_at)
                        values(?,?,?,?,?,'받는사람','010-0000-0000','1990-01-01','M',NOW(6),NOW(6))
                        """, UUID.randomUUID().toString(), eventId, row.getId(), itemId, categoryId);
            }
        });
    }

    /** 대회 행 잠금 덕분에 한 요청만 번호를 주고 다른 요청은 0건이며, 번호는 겹치지 않고 순번이 이어진다. */
    @Test
    void concurrentIssuesDoNotOverlap() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<BibIssueResponse>> results = List.of(
                    executor.submit(() -> issueAfter(ready, go)), executor.submit(() -> issueAfter(ready, go)));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            int first = results.get(0).get(30, TimeUnit.SECONDS).issuedCount();
            int second = results.get(1).get(30, TimeUnit.SECONDS).issuedCount();
            assertThat(List.of(first, second)).containsExactlyInAnyOrder(REGISTRATIONS, 0);
        } finally {
            executor.shutdownNow();
        }

        List<String> bibs = jdbc.queryForList("select bib_number from registration_entry where event_id=? order by bib_number", String.class, eventId);
        assertThat(bibs).containsExactly("10001", "10002", "10003", "10004", "10005");
        assertThat(jdbc.queryForObject("select last_number from bib_sequence where event_id=? and event_category_id=?",
                Integer.class, eventId, categoryId)).isEqualTo(10000 + REGISTRATIONS);
    }

    private BibIssueResponse issueAfter(CountDownLatch ready, CountDownLatch go) throws InterruptedException {
        ready.countDown();
        go.await(10, TimeUnit.SECONDS);
        return service.issue(eventId, new BibIssueRequest(DEADLINE));
    }

    /** 만든 대회의 데이터만 FK 역순으로 지운다. */
    @AfterEach
    void cleanup() {
        if (eventId == null || tx == null) return;
        tx.executeWithoutResult(status -> {
            jdbc.update("delete from registration_entry where event_id=?", eventId);
            jdbc.update("delete from registration_item where event_id=?", eventId);
            jdbc.update("delete from registration where event_id=?", eventId);
            jdbc.update("delete from bib_sequence where event_id=?", eventId);
            jdbc.update("delete g from event_category_start_group g join event_category c on c.id=g.event_category_id where c.event_id=?", eventId);
            jdbc.update("delete from event_category where event_id=?", eventId);
            jdbc.update("delete from registration_action_policy where event_id=?", eventId);
            jdbc.update("delete from event where id=?", eventId);
        });
    }
}
