package kr.co.teambrain.marvelrun.admin.event;

import jakarta.persistence.EntityManager;
import kr.co.teambrain.marvelrun.admin.common.exception.CustomException;
import kr.co.teambrain.marvelrun.admin.common.exception.ErrorCode;
import kr.co.teambrain.marvelrun.admin.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.RegistrationEntry;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.BibIssueRequest;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.BibIssueResponse;
import kr.co.teambrain.marvelrun.admin.event.command.application.dto.RegistrationEntryModifyRequest;
import kr.co.teambrain.marvelrun.admin.event.command.application.service.BibIssueService;
import kr.co.teambrain.marvelrun.admin.event.command.application.service.RegistrationEntryCommandService;
import kr.co.teambrain.marvelrun.admin.event.command.application.valid.RegistrationPolicyValidator;
import kr.co.teambrain.marvelrun.admin.event.command.application.valid.loader.RegistrationPolicyLoader;
import kr.co.teambrain.marvelrun.admin.event.command.repository.BibIssueRepository;
import kr.co.teambrain.marvelrun.admin.event.command.repository.RegistrationEntryCommandRepository;
import kr.co.teambrain.marvelrun.admin.event.query.support.RegistrationActionPolicyReader;
import kr.co.teambrain.marvelrun.common.inheritance_enum.GenderClass;
import kr.co.teambrain.marvelrun.common.inheritance_enum.RegistrationStatus;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** 실제 DB에서 배번 발급의 대상 선정·순서·번호 대역·롤백 조건을 검증하고 테스트 종료 시 롤백한다. */
@EnabledIfEnvironmentVariable(named = "MARVELRUN_BIB_ISSUE_DB_TEST", matches = "true")
@ActiveProfiles("admin-refund-test")
@DataJpaTest(showSql = false, properties = {
        "spring.datasource.url=${MARVELRUN_TEST_DB_URL}",
        "spring.datasource.username=${MARVELRUN_TEST_DB_USERNAME}",
        "spring.datasource.password=${MARVELRUN_TEST_DB_PASSWORD}",
        "spring.jpa.hibernate.ddl-auto=none", "spring.sql.init.mode=never",
        "spring.flyway.enabled=false", "spring.liquibase.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({BibIssueService.class, BibIssueRepository.class, RegistrationActionPolicyReader.class,
        RegistrationEntryCommandService.class, RegistrationPolicyLoader.class, RegistrationPolicyValidator.class})
class BibIssueDatabaseTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 1, 0, 0);
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 11, 20, 17, 0);
    private static final LocalDateTime NOW = DEADLINE.plusHours(1);

    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired BibIssueService service;
    @Autowired RegistrationEntryCommandService entryCommands;
    @Autowired RegistrationEntryCommandRepository entries;
    @MockitoBean ServerTimeProvider time;

    private String eventId;
    private String catA;
    private String catB;
    private String catC;
    private String pkg;

    /** 버추얼 대회, 패키지와 하위 단품 3개, 1차수 정책, 홈페이지 배번 순번을 만든다. */
    @BeforeEach
    void fixture() {
        when(time.currentDateTime()).thenReturn(NOW);
        eventId = id();
        jdbc.update("""
                insert into event(id,name_kr,start_date,region,host,organizer,event_status,visible_status,
                    regist_start_date,regist_deadline,payment_deadline,auto_max_regist,auto_start,auto_deadline,phone_auth_required,event_format)
                values(?, '배번 테스트', ?, 'test','test','test','OPEN','OPEN',?,?,NULL,true,true,true,false,'VIRTUAL')
                """, eventId, DEADLINE.plusDays(7), START, LocalDateTime.of(2027, 1, 19, 23, 59, 59));
        pkg = category("패키지", 4, "PACKAGE", null);
        catA = category("A", 1, "SINGLE", pkg);
        catB = category("B", 2, "SINGLE", pkg);
        catC = category("C", 3, "SINGLE", pkg);
        for (String action : List.of("MODIFY", "PAYMENT", "REFUND")) {
            jdbc.update("insert into registration_action_policy(id,event_id,action_type,registration_start_at,registration_end_at,effective_from,enabled) values(?,?,?,?,?,?,1)",
                    id(), eventId, action, START, DEADLINE, DEADLINE);
        }
        sequence(catA, 10001);
        sequence(catB, 20001);
        sequence(catC, 30001);
    }

    /** 순서·대역·제외 조건·건수·last_number를 확인하고 두 번째 실행은 아무것도 발급하지 않는다. */
    @Test
    void issuesInRegistrationOrderPerCategoryBand() {
        Registration r1 = registration(catA, RegistrationStatus.CONFIRMED, false, false, DEADLINE.minusHours(3));
        Registration r2 = registration(pkg, RegistrationStatus.CONFIRMED, false, false, DEADLINE.minusHours(2));
        Registration r3 = registration(catB, RegistrationStatus.CONFIRMED, false, false, DEADLINE.minusHours(1));
        // 제외 대상: 삭제, 미확정, 마감 뒤, 외부 결제(항목 있음·없음), 이미 발급, 항목 없음
        Registration deleted = registration(catA, RegistrationStatus.CONFIRMED, true, false, DEADLINE.minusHours(5));
        Registration pending = registration(catA, RegistrationStatus.PAYMENT_PENDING, false, false, DEADLINE.minusHours(5));
        Registration late = registration(catA, RegistrationStatus.CONFIRMED, false, false, DEADLINE.plusMinutes(30));
        Registration external = registration(catA, RegistrationStatus.CONFIRMED, false, true, DEADLINE.minusHours(5));
        Registration issued = registration(catA, RegistrationStatus.CONFIRMED, false, false, DEADLINE.minusHours(6));
        registration(catB, RegistrationStatus.CONFIRMED, false, false, DEADLINE.minusHours(7));
        // 항목 없는 외부 결제는 외부 결제 수에만 잡히고 항목 누락 수에는 잡히지 않는다.
        registration(catA, RegistrationStatus.CONFIRMED, false, true, DEADLINE.minusHours(8));
        em.flush();
        String e1 = entry(r1, catA);
        String e2a = entry(r2, catA);
        String e2b = entry(r2, catB);
        String e2c = entry(r2, catC);
        String e3 = entry(r3, catB);
        entry(deleted, catA);
        entry(pending, catA);
        entry(late, catA);
        entry(external, catA);
        String eIssued = entry(issued, catA);
        jdbc.update("update registration_entry set bib_number='19999', bib_issued_at=? where id=?", NOW.minusDays(1), eIssued);

        BibIssueResponse response = service.issue(eventId, new BibIssueRequest(DEADLINE));

        assertThat(bib(e1)).isEqualTo("10001");
        assertThat(bib(e2a)).isEqualTo("10002");
        assertThat(bib(e2b)).isEqualTo("20001");
        assertThat(bib(e3)).isEqualTo("20002");
        assertThat(bib(e2c)).isEqualTo("30001");
        assertThat(bib(eIssued)).isEqualTo("19999");
        assertThat(jdbc.queryForObject("select count(*) from registration_entry where event_id=? and bib_number is not null", Integer.class, eventId)).isEqualTo(6);
        assertThat(jdbc.queryForObject("select bib_issued_at from registration_entry where id=?", LocalDateTime.class, e1)).isEqualTo(NOW);
        assertThat(response.issuedCount()).isEqualTo(5);
        assertThat(response.alreadyIssuedCount()).isEqualTo(1);
        assertThat(response.externalPaymentSkippedCount()).isEqualTo(2);
        assertThat(response.registrationsWithoutEntriesCount()).isEqualTo(1);
        assertThat(response.perCategory()).extracting(BibIssueResponse.PerCategory::eventCategoryId, BibIssueResponse.PerCategory::issuedCount,
                BibIssueResponse.PerCategory::firstNumber, BibIssueResponse.PerCategory::lastNumber)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(catA, 2, 10001, 10002),
                        org.assertj.core.groups.Tuple.tuple(catB, 2, 20001, 20002),
                        org.assertj.core.groups.Tuple.tuple(catC, 1, 30001, 30001));
        assertThat(last(catA)).isEqualTo(10002);
        assertThat(last(catB)).isEqualTo(20002);
        assertThat(last(catC)).isEqualTo(30001);

        BibIssueResponse again = service.issue(eventId, new BibIssueRequest(DEADLINE));
        assertThat(again.issuedCount()).isZero();
        assertThat(again.alreadyIssuedCount()).isEqualTo(6);
        assertThat(last(catA)).isEqualTo(10002);
    }

    /**
     * 한 주문에 같은 종목 구매 줄이 둘이면 앞 줄의 항목이 앞 번호를 받는다(항목 ID 순서와 무관).
     * 같은 줄 안에서는 종목 순서다. 참가 항목이 없는 구매 줄이 있는 주문은 누락 수에 잡힌다.
     */
    @Test
    void ordersByItemThenCategoryAndCountsItemsWithoutEntries() {
        Registration order = registration(catA, RegistrationStatus.CONFIRMED, false, false, DEADLINE.minusHours(3));
        Registration missing = registration(catA, RegistrationStatus.CONFIRMED, false, false, DEADLINE.minusHours(2));
        em.flush();
        String firstItem = item(order.getId());
        String secondItem = addItem(order.getId(), catA, 2);
        String packageItem = addItem(order.getId(), pkg, 3);
        String laterEntry = entryInItem(order, firstItem, catA, "ffffffff-0000-0000-0000-000000000000");
        String earlierEntry = entryInItem(order, secondItem, catA, "00000000-0000-0000-0000-000000000000");
        String packageB = entryInItem(order, packageItem, catB, "00000000-0000-0000-0000-000000000001");
        String packageA = entryInItem(order, packageItem, catA, "ffffffff-0000-0000-0000-000000000001");
        entry(missing, catA);
        addItem(missing.getId(), catB, 2);

        BibIssueResponse response = service.issue(eventId, new BibIssueRequest(DEADLINE));

        assertThat(bib(laterEntry)).isEqualTo("10001");
        assertThat(bib(earlierEntry)).isEqualTo("10002");
        assertThat(bib(packageA)).isEqualTo("10003");
        assertThat(bib(packageB)).isEqualTo("20001");
        assertThat(response.issuedCount()).isEqualTo(5);
        assertThat(response.registrationsWithoutEntriesCount()).isEqualTo(1);
    }

    /** 부분 취소한 줄의 항목은 배번을 받지 않고, 취소한 빈 줄은 "항목이 없는 줄"로 세지 않는다. */
    @Test
    void skipsCanceledLines() {
        Registration order = registration(catA, RegistrationStatus.CONFIRMED, false, false, DEADLINE.minusHours(3));
        em.flush();
        String canceledItem = item(order.getId());
        String liveItem = addItem(order.getId(), catA, 2);
        String emptyCanceledItem = addItem(order.getId(), catB, 3);
        String canceledEntry = entryInItem(order, canceledItem, catA, "00000000-0000-0000-0000-000000000000");
        String liveEntry = entryInItem(order, liveItem, catA, "ffffffff-0000-0000-0000-000000000000");
        jdbc.update("update registration_item set canceled_at = ? where id in (?, ?)", NOW.minusDays(1), canceledItem, emptyCanceledItem);

        BibIssueResponse response = service.issue(eventId, new BibIssueRequest(DEADLINE));

        assertThat(bib(liveEntry)).isEqualTo("10001");
        assertThat(bib(canceledEntry)).isNull();
        assertThat(response.issuedCount()).isEqualTo(1);
        assertThat(response.registrationsWithoutEntriesCount()).isZero();
    }

    /** 빈 구매 줄이 여럿인 신청도 한 번만 센다. 구매 줄이 없고 대표 종목이 상품이 아닌 신청은 세지 않는다. */
    @Test
    void countsRegistrationWithEmptyItemsOnceAndSkipsNonProduct() {
        Registration emptyItems = registration(catA, RegistrationStatus.CONFIRMED, false, false, DEADLINE.minusHours(3));
        String general = category("일반 종목", 5, null, null);
        registration(general, RegistrationStatus.CONFIRMED, false, false, DEADLINE.minusHours(2));
        em.flush();
        item(emptyItems.getId());
        addItem(emptyItems.getId(), catB, 2);

        BibIssueResponse response = service.issue(eventId, new BibIssueRequest(DEADLINE));

        assertThat(response.issuedCount()).isZero();
        assertThat(response.registrationsWithoutEntriesCount()).isEqualTo(1);
    }

    /** 순번이 이어지는 두 번째 발급은 last_number 다음 번호부터 준다. */
    @Test
    void continuesFromLastNumber() {
        Registration r1 = registration(catA, RegistrationStatus.CONFIRMED, false, false, DEADLINE.minusHours(3));
        em.flush();
        String e1 = entry(r1, catA);
        service.issue(eventId, new BibIssueRequest(DEADLINE));
        Registration r2 = registration(catA, RegistrationStatus.CONFIRMED, false, false, DEADLINE.minusHours(2));
        em.flush();
        String e2 = entry(r2, catA);

        service.issue(eventId, new BibIssueRequest(DEADLINE));

        assertThat(bib(e1)).isEqualTo("10001");
        assertThat(bib(e2)).isEqualTo("10002");
        assertThat(last(catA)).isEqualTo(10002);
    }

    /** 대상 종목의 순번 행이 없으면 아무것도 쓰지 않는다. */
    @Test
    void missingSequenceWritesNothing() {
        Registration r1 = registration(catA, RegistrationStatus.CONFIRMED, false, false, DEADLINE.minusHours(3));
        Registration r2 = registration(catC, RegistrationStatus.CONFIRMED, false, false, DEADLINE.minusHours(2));
        em.flush();
        entry(r1, catA);
        entry(r2, catC);
        jdbc.update("delete from bib_sequence where event_id=? and event_category_id=?", eventId, catC);

        assertError(DEADLINE, ErrorCode.BIB_SEQUENCE_NOT_CONFIGURED);

        assertThat(jdbc.queryForObject("select count(*) from registration_entry where event_id=? and bib_number is not null", Integer.class, eventId)).isZero();
        assertThat(last(catA)).isNull();
    }

    /** 마감 전 신청의 상향 대기가 있으면 거절하고, 마감 뒤 신청의 대기는 막지 않는다. */
    @Test
    void pendingUpgradeBlocksOnlyBeforeDeadline() {
        Registration late = registration(catA, RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED, false, false, DEADLINE.plusMinutes(10));
        Registration early = registration(catA, RegistrationStatus.ADDITIONAL_PAYMENT_REQUIRED, false, false, DEADLINE.minusHours(1));
        em.flush();
        upgrade(late);
        service.issue(eventId, new BibIssueRequest(DEADLINE));

        upgrade(early);
        assertError(DEADLINE, ErrorCode.BIB_ISSUE_UPGRADE_PENDING);
    }

    /** 마감 전, 없는 차수, 버추얼이 아닌 대회는 거절한다. */
    @Test
    void rejectsNotClosedUnknownRoundAndNonVirtual() {
        when(time.currentDateTime()).thenReturn(DEADLINE.minusSeconds(1));
        assertError(DEADLINE, ErrorCode.BIB_ISSUE_ROUND_NOT_CLOSED);

        when(time.currentDateTime()).thenReturn(NOW);
        assertError(DEADLINE.minusDays(1), ErrorCode.VIRTUAL_ROUND_NOT_FOUND);

        jdbc.update("update event set event_format='OFFLINE' where id=?", eventId);
        em.clear();
        assertError(DEADLINE, ErrorCode.VIRTUAL_EVENT_REQUIRED);
    }

    /** 받는 사람을 엔티티로 고쳐도, 먼저 읽어 둔 항목이 JDBC로 발급된 배번을 NULL로 되돌리지 않는다. */
    @Test
    void entityUpdateKeepsBibIssuedByJdbc() {
        Registration registration = registration(catA, RegistrationStatus.CONFIRMED, false, false, DEADLINE.minusHours(3));
        em.flush();
        String entryId = entry(registration, catA);
        em.clear();
        RegistrationEntry loaded = entries.findById(entryId).orElseThrow();
        assertThat(loaded.getBibNumber()).isNull();

        service.issue(eventId, new BibIssueRequest(DEADLINE));
        loaded.modifyRecipientByAdmin("변경된이름", loaded.getPhNum(), loaded.getBirth(), loaded.getGender());
        em.flush();

        assertThat(bib(entryId)).isEqualTo("10001");
        assertThat(jdbc.queryForObject("select name from registration_entry where id=?", String.class, entryId)).isEqualTo("변경된이름");
    }

    /** 패키지 받는 사람을 배번 발급 뒤(마감 뒤)에 관리자가 고쳐도 배번은 남고 이름만 바뀐다. */
    @Test
    void adminEntryEditKeepsIssuedBib() {
        jdbc.update("insert into event_registration_policy(id,event_id,guardian_required_birth_from) values(?,?,'2013-11-01')", id(), eventId);
        for (String category : List.of(catA, catB, catC)) {
            jdbc.update("insert into event_category_registration_policy(id,event_category_id,allowed_birth_from,allowed_birth_to) values(?,?,null,null)", id(), category);
        }
        Registration registration = registration(pkg, RegistrationStatus.CONFIRMED, false, false, DEADLINE.minusHours(3));
        em.flush();
        String entryA = entry(registration, catA);
        String entryB = entry(registration, catB);
        service.issue(eventId, new BibIssueRequest(DEADLINE));
        em.clear();

        var response = entryCommands.modifyEntries(registration.getId(), new RegistrationEntryModifyRequest(List.of(
                new RegistrationEntryModifyRequest.Entry(entryA, "수정된이름", "01099998888", "1992-03-04", GenderClass.F))));
        em.flush();
        em.clear();

        assertThat(response.entries()).singleElement().satisfies(e -> assertThat(e.bibNumber()).isEqualTo("10001"));
        assertThat(bib(entryA)).isEqualTo("10001");
        assertThat(jdbc.queryForObject("select name from registration_entry where id=?", String.class, entryA)).isEqualTo("수정된이름");
        assertThat(jdbc.queryForObject("select ph_num from registration_entry where id=?", String.class, entryA)).isEqualTo("01099998888");
        assertThat(jdbc.queryForObject("select name from registration_entry where id=?", String.class, entryB)).isEqualTo("받는사람");
        assertThat(bib(entryB)).isEqualTo("20001");
        assertThat(jdbc.queryForObject("select detail_memo from registration where id=?", String.class, registration.getId())).contains("패키지 받는 사람 정보 수정");
    }

    private void assertError(LocalDateTime deadline, ErrorCode code) {
        assertThatThrownBy(() -> service.issue(eventId, new BibIssueRequest(deadline)))
                .isInstanceOfSatisfying(CustomException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private String bib(String entryId) {
        return jdbc.queryForObject("select bib_number from registration_entry where id=?", String.class, entryId);
    }

    private Integer last(String categoryId) {
        return jdbc.queryForObject("select last_number from bib_sequence where event_id=? and event_category_id=? and channel='HOMEPAGE'",
                Integer.class, eventId, categoryId);
    }

    private String category(String name, long order, String productType, String parent) {
        String id = id();
        jdbc.update("insert into event_category(id,event_id,amount,name,is_active,sort_order,distance_meters,product_type,parent_category_id) values(?,?,40000,?,true,?,5000,?,?)",
                id, eventId, name, order, productType, parent);
        jdbc.update("INSERT INTO event_category_start_group (id,event_category_id,code,description,sort_order,min_pace_seconds_per_km,min_expected_finish_seconds,is_active) VALUES (UUID(),?,'ALL','버추얼런',10,0,0,1)", id);
        return id;
    }

    private void sequence(String categoryId, int start) {
        jdbc.update("insert into bib_sequence(event_id,event_category_id,channel,start_number,last_number) values(?,?,'HOMEPAGE',?,NULL)", eventId, categoryId, start);
    }

    private Registration registration(String categoryId, RegistrationStatus status, boolean deleted, boolean external, LocalDateTime date) {
        Registration row = Registration.builder()
                .startGroupId(jdbc.queryForObject("select id from event_category_start_group where event_category_id=?", String.class, categoryId))
                .startGroupUnselected(true).event(em.getReference(Event.class, eventId)).eventCategory(em.getReference(EventCategory.class, categoryId))
                .name("test-" + id().substring(0, 8)).phNum("010-0000-0000").birth("1990-01-01")
                .gender(GenderClass.M).password("fixture").address("주소").addressDetail("상세").souvenirJson(List.of())
                .status(status).softDeleted(deleted).externalPayment(external)
                .contractAmount(new BigDecimal("40000")).paidAmount(new BigDecimal("40000"))
                .termsEssentialAgreed(true).termsMarketingAgreed(false).termsMarketingChannelAgreed(false).termsAgreedAt(START).build();
        em.persist(row);
        em.flush();
        jdbc.update("update registration set registration_date=? where id=?", date, row.getId());
        return row;
    }

    private String entry(Registration registration, String categoryId) {
        String id = id();
        jdbc.update("""
                insert into registration_entry(id,event_id,registration_id,registration_item_id,event_category_id,name,ph_num,birth,gender,created_at,updated_at)
                values(?,?,?,?,?,'받는사람','010-0000-0000','1990-01-01','M',NOW(6),NOW(6))
                """, id, eventId, registration.getId(), item(registration.getId()), categoryId);
        return id;
    }

    /** 신청의 첫 구매 줄. 없으면 신청 종목·계약금액으로 만든다. 참가 항목은 구매 줄에 묶어야 한다. */
    private String item(String registrationId) {
        List<String> found = jdbc.queryForList(
                "select id from registration_item where registration_id=? and sort_order=1", String.class, registrationId);
        if (!found.isEmpty()) {
            return found.getFirst();
        }
        String itemId = UUID.randomUUID().toString();
        jdbc.update("""
                insert into registration_item(id,event_id,registration_id,event_category_id,sort_order,amount,souvenir_json)
                select ?,event_id,id,event_category_id,1,contract_amount,souvenir_json from registration where id=?
                """, itemId, registrationId);
        return itemId;
    }

    /** 신청에 구매 줄을 더한다. */
    private String addItem(String registrationId, String categoryId, int sortOrder) {
        String itemId = UUID.randomUUID().toString();
        jdbc.update("""
                insert into registration_item(id,event_id,registration_id,event_category_id,sort_order,amount,souvenir_json)
                select ?,event_id,id,?,?,40000,souvenir_json from registration where id=?
                """, itemId, categoryId, sortOrder, registrationId);
        return itemId;
    }

    /** 지정한 구매 줄에 ID를 정해 참가 항목을 더한다. */
    private String entryInItem(Registration registration, String itemId, String categoryId, String entryId) {
        jdbc.update("""
                insert into registration_entry(id,event_id,registration_id,registration_item_id,event_category_id,name,ph_num,birth,gender,created_at,updated_at)
                values(?,?,?,?,?,'받는사람','010-0000-0000','1990-01-01','M',NOW(6),NOW(6))
                """, entryId, eventId, registration.getId(), itemId, categoryId);
        return entryId;
    }

    private void upgrade(Registration registration) {
        jdbc.update("""
                insert into package_upgrade(id,event_id,registration_id,target_category_id,amount,status,added_entries,held_capacities)
                values(?,?,?,?,10000,'PENDING','[]','[]')
                """, id(), eventId, registration.getId(), pkg);
    }

    private String id() {
        return UUID.randomUUID().toString();
    }
}
