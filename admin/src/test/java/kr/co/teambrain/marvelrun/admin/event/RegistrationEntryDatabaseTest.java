package kr.co.teambrain.marvelrun.admin.event;

import jakarta.persistence.EntityManager;
import kr.co.teambrain.marvelrun.admin.common.time.ServerTimeProvider;
import kr.co.teambrain.marvelrun.admin.discount.query.ReferenceFilterQueryService;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Event;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.EventCategory;
import kr.co.teambrain.marvelrun.admin.event.command.application.domain.Registration;
import kr.co.teambrain.marvelrun.admin.event.query.dto.RegistrationSearchCondition;
import kr.co.teambrain.marvelrun.admin.event.query.dto.response.RegistrationDetailResponse;
import kr.co.teambrain.marvelrun.admin.event.query.dto.response.RegistrationEntryResponse;
import kr.co.teambrain.marvelrun.admin.event.query.dto.response.RegistrationListResponse;
import kr.co.teambrain.marvelrun.admin.event.query.service.RegistrationQueryService;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** 실제 DB에서 신청 목록의 배번 표시·배번 검색과 건수가 참가 항목 때문에 부풀지 않는지 검증하고 롤백한다. */
@EnabledIfEnvironmentVariable(named = "MARVELRUN_REGISTRATION_ENTRY_DB_TEST", matches = "true")
@ActiveProfiles("admin-refund-test")
@DataJpaTest(showSql = false, properties = {
        "spring.datasource.url=${MARVELRUN_TEST_DB_URL}",
        "spring.datasource.username=${MARVELRUN_TEST_DB_USERNAME}",
        "spring.datasource.password=${MARVELRUN_TEST_DB_PASSWORD}",
        "spring.jpa.hibernate.ddl-auto=none", "spring.sql.init.mode=never",
        "spring.flyway.enabled=false", "spring.liquibase.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ReferenceFilterQueryService.class, RegistrationQueryService.class, RegistrationActionPolicyReader.class})
class RegistrationEntryDatabaseTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 12, 0);

    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired RegistrationQueryService queries;
    @Autowired kr.co.teambrain.marvelrun.admin.event.command.repository.RegistrationCommandRepository registrationCommands;
    @MockitoBean ServerTimeProvider time;

    private String eventId;
    private String pkg;
    private String catA;
    private String catB;
    private String catC;
    private Registration pkgRegistration;
    private Registration singleRegistration;

    /** 버추얼 대회에 패키지(하위 3종)와 단품 신청을 만들고 항목마다 배번을 둔다. */
    @BeforeEach
    void fixture() {
        when(time.currentDateTime()).thenReturn(NOW);
        eventId = event("VIRTUAL");
        pkg = category(eventId, "패키지", 4, "PACKAGE", null);
        catA = category(eventId, "5km | A", 1, "SINGLE", pkg);
        catB = category(eventId, "10km | B", 2, "SINGLE", pkg);
        catC = category(eventId, "3km | C", 3, "SINGLE", pkg);
        pkgRegistration = registration(eventId, pkg);
        singleRegistration = registration(eventId, catA);
        em.flush();
        // 하위 종목 정렬 순서(A, B, C)와 ID 순서가 다르도록 항목을 거꾸로 넣는다.
        entry(eventId, pkgRegistration, catC, "30001");
        entry(eventId, pkgRegistration, catB, "20001");
        entry(eventId, pkgRegistration, catA, "10001");
        entry(eventId, singleRegistration, catA, "10002");
        em.flush();
        em.clear();
    }

    /** 패키지 항목이 여러 개여도 건수는 신청 수 그대로이고, 배번은 하위 종목 순서로 모두 나온다. */
    @Test
    void listIsNotInflatedAndShowsBibNumbersInCategoryOrder() {
        Page<RegistrationListResponse> page = queries.getRegistrationList(condition(eventId, null), page());

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(2);
        assertThat(bibs(page, pkgRegistration)).containsExactly("10001", "20001", "30001");
        assertThat(bibs(page, singleRegistration)).containsExactly("10002");
    }

    /** 신청 상세의 참가 항목은 entryId와 배번을 하위 종목 순서로 돌려주고, 상품 신청(단품·패키지)은 받는 사람을 고칠 수 있다. */
    @Test
    void detailReturnsEntriesInCategoryOrder() {
        RegistrationDetailResponse response = queries.getRegistrationDetail(pkgRegistration.getId());

        assertThat(response.entriesEditable()).isTrue();
        assertThat(response.entries()).hasSize(3);
        assertThat(response.entries()).allSatisfy(entry -> assertThat(entry.entryId()).isNotBlank());
        assertThat(response.entries()).extracting(RegistrationEntryResponse::bibNumber)
                .containsExactly("10001", "20001", "30001");
        assertThat(response.entries()).extracting(RegistrationEntryResponse::eventCategoryId)
                .containsExactly(catA, catB, catC);
        // 단품 받는 사람도 신청자 값을 따르지 않으므로 여기서 고친다.
        assertThat(queries.getRegistrationDetail(singleRegistration.getId()).entriesEditable()).isTrue();
    }

    /**
     * 같은 신청자 주문 여러 건 허용 신청의 신원 검사용 잠금 읽기는 본인과 삭제 이력을 빼고 같은 신원의 살아 있는 신청만 센다.
     * duplicate_allowed 행은 유니크 인덱스에서 빠지므로 같은 신원 두 행을 만들 수 있다.
     */
    @Test
    void lockingIdentityCountIgnoresSelfAndDeletedRows() {
        jdbc.update("update registration set duplicate_allowed = 1, name = '같은신청자' where id in (?, ?)",
                pkgRegistration.getId(), singleRegistration.getId());

        assertThat(registrationCommands.countOtherActiveByEventIdAndUniqueInfoForShare(
                eventId, pkgRegistration.getId(), "같은신청자", "010-0000-0000", "1990-01-01")).isEqualTo(1);
        jdbc.update("update registration set is_del = 1 where id = ?", singleRegistration.getId());
        assertThat(registrationCommands.countOtherActiveByEventIdAndUniqueInfoForShare(
                eventId, pkgRegistration.getId(), "같은신청자", "010-0000-0000", "1990-01-01")).isZero();
    }

    /** 삭제된 패키지 신청은 상세를 볼 수 있어도 받는 사람을 고칠 수 없다. */
    @Test
    void deletedPackageIsNotEditable() {
        jdbc.update("update registration set is_del=1 where id=?", pkgRegistration.getId());

        assertThat(queries.getRegistrationDetail(pkgRegistration.getId()).entriesEditable()).isFalse();
    }

    /** 배번은 정확히 일치할 때만 찾고, 다른 대회의 같은 배번은 잡히지 않는다. */
    @Test
    void bibSearchMatchesExactlyWithinEvent() {
        String otherEvent = event("VIRTUAL");
        String otherCategory = category(otherEvent, "다른 대회 종목", 1, "SINGLE", null);
        Registration other = registration(otherEvent, otherCategory);
        em.flush();
        entry(otherEvent, other, otherCategory, "20001");
        em.flush();
        em.clear();

        Page<RegistrationListResponse> found = queries.getRegistrationList(condition(eventId, "20001"), page());
        assertThat(found.getTotalElements()).isEqualTo(1);
        assertThat(found.getContent()).extracting(RegistrationListResponse::registrationId)
                .containsExactly(pkgRegistration.getId());
        // 검색해도 신청의 배번은 모두 보인다.
        assertThat(found.getContent().getFirst().bibNumbers()).containsExactly("10001", "20001", "30001");

        assertThat(queries.getRegistrationList(condition(eventId, "2000"), page())).isEmpty();
        Page<RegistrationListResponse> otherFound = queries.getRegistrationList(condition(otherEvent, "20001"), page());
        assertThat(otherFound.getContent()).extracting(RegistrationListResponse::registrationId)
                .containsExactly(other.getId());
    }

    /** 오프라인 대회의 목록은 배번이 비어 있고 정상 조회된다. */
    @Test
    void offlineEventListHasNoBibNumbers() {
        String offline = event("OFFLINE");
        String category = category(offline, "오프라인 종목", 1, null, null);
        registration(offline, category);
        em.flush();
        em.clear();

        Page<RegistrationListResponse> page = queries.getRegistrationList(condition(offline, null), page());

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().getFirst().bibNumbers()).isEmpty();
    }

    private List<String> bibs(Page<RegistrationListResponse> page, Registration registration) {
        return page.getContent().stream().filter(row -> row.registrationId().equals(registration.getId()))
                .findFirst().orElseThrow().bibNumbers();
    }

    private RegistrationSearchCondition condition(String event, String bib) {
        return new RegistrationSearchCondition(event, null, null, null, null, null, null, null, bib);
    }

    private PageRequest page() {
        return PageRequest.of(0, 10, Sort.by(Sort.Order.desc("registrationDate"), Sort.Order.desc("id")));
    }

    private String event(String format) {
        String id = id();
        jdbc.update("""
                insert into event(id,name_kr,start_date,region,host,organizer,event_status,visible_status,
                    regist_start_date,regist_deadline,payment_deadline,auto_max_regist,auto_start,auto_deadline,phone_auth_required,event_format)
                values(?, '배번 목록 테스트', ?, 'test','test','test','OPEN','OPEN',?,?,NULL,true,true,true,false,?)
                """, id, NOW.plusDays(7), NOW.minusDays(10), NOW.plusDays(30), format);
        return id;
    }

    private String category(String event, String name, long order, String productType, String parent) {
        String id = id();
        jdbc.update("insert into event_category(id,event_id,amount,name,is_active,sort_order,distance_meters,product_type,parent_category_id) values(?,?,40000,?,true,?,5000,?,?)",
                id, event, name, order, productType, parent);
        jdbc.update("INSERT INTO event_category_start_group (id,event_category_id,code,description,sort_order,min_pace_seconds_per_km,min_expected_finish_seconds,is_active) VALUES (UUID(),?,'ALL','테스트',10,0,0,1)", id);
        return id;
    }

    private Registration registration(String event, String categoryId) {
        Registration row = Registration.builder()
                .startGroupId(jdbc.queryForObject("select id from event_category_start_group where event_category_id=?", String.class, categoryId))
                .startGroupUnselected(true).event(em.getReference(Event.class, event)).eventCategory(em.getReference(EventCategory.class, categoryId))
                .name("test-" + id().substring(0, 8)).phNum("010-0000-0000").birth("1990-01-01")
                .gender(GenderClass.M).password("fixture").address("주소").addressDetail("상세").souvenirJson(List.of())
                .status(RegistrationStatus.CONFIRMED).softDeleted(false).externalPayment(false)
                .contractAmount(new BigDecimal("40000")).paidAmount(new BigDecimal("40000"))
                .termsEssentialAgreed(true).termsMarketingAgreed(false).termsMarketingChannelAgreed(false).termsAgreedAt(NOW).build();
        em.persist(row);
        return row;
    }

    private void entry(String event, Registration registration, String categoryId, String bib) {
        jdbc.update("""
                insert into registration_entry(id,event_id,registration_id,registration_item_id,event_category_id,name,ph_num,birth,gender,bib_number,bib_issued_at,created_at,updated_at)
                values(?,?,?,?,?,'받는사람','010-0000-0000','1990-01-01','M',?,NOW(6),NOW(6),NOW(6))
                """, id(), event, registration.getId(), item(registration.getId()), categoryId, bib);
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

    private String id() {
        return UUID.randomUUID().toString();
    }
}
